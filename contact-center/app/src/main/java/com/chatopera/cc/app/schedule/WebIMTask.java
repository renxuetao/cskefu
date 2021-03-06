/*
 * Copyright (C) 2018 Chatopera Inc, <https://www.chatopera.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.chatopera.cc.app.schedule;

import com.chatopera.cc.app.algorithm.AutomaticServiceDist;
import com.chatopera.cc.app.basic.MainContext;
import com.chatopera.cc.app.basic.MainUtils;
import com.chatopera.cc.app.cache.CacheHelper;
import com.chatopera.cc.app.im.client.NettyClients;
import com.chatopera.cc.app.im.message.ChatMessage;
import com.chatopera.cc.app.im.router.OutMessageRouter;
import com.chatopera.cc.app.model.*;
import com.chatopera.cc.app.persistence.impl.CallOutQuene;
import com.chatopera.cc.app.persistence.repository.*;
import com.chatopera.cc.exchange.DataExchangeInterface;
import com.chatopera.cc.util.OnlineUserUtils;
import com.chatopera.cc.util.freeswitch.model.CallCenterAgent;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

@Configuration
@EnableScheduling
public class WebIMTask {

    @Autowired
    private AgentUserTaskRepository agentUserTaskRes;

    @Autowired
    private OnlineUserRepository onlineUserRes;

    @Autowired
    private JobDetailRepository jobDetailRes;

    @Autowired
    private TaskExecutor webimTaskExecutor;

    @Scheduled(fixedDelay = 5000) // ????????????????????????5???????????????
    public void task() {
        List<SessionConfig> sessionConfigList = AutomaticServiceDist.initSessionConfigList();
        if (sessionConfigList != null && sessionConfigList.size() > 0 && MainContext.getContext() != null) {
            for (SessionConfig sessionConfig : sessionConfigList) {
                if (sessionConfig.isSessiontimeout()) {        //??????????????? ????????????
                    List<AgentUserTask> agentUserTask = agentUserTaskRes.findByLastmessageLessThanAndStatusAndOrgi(MainUtils.getLastTime(sessionConfig.getTimeout()), MainContext.AgentUserStatusEnum.INSERVICE.toString(), sessionConfig.getOrgi());
                    for (AgentUserTask task : agentUserTask) {        // ???????????????
                        AgentUser agentUser = (AgentUser) CacheHelper.getAgentUserCacheBean().getCacheObject(task.getUserid(), MainContext.SYSTEM_ORGI);
                        if (agentUser != null && agentUser.getAgentno() != null) {
                            AgentStatus agentStatus = (AgentStatus) CacheHelper.getAgentStatusCacheBean().getCacheObject(agentUser.getAgentno(), task.getOrgi());
                            task.setAgenttimeouttimes(task.getAgenttimeouttimes() + 1);
                            if (agentStatus != null && (task.getWarnings() == null || task.getWarnings().equals("0"))) {
                                task.setWarnings("1");
                                task.setWarningtime(new Date());

                                //??????????????????
                                processMessage(sessionConfig, sessionConfig.getTimeoutmsg(), agentStatus.getUsername(), agentUser, agentStatus, task);
                                agentUserTaskRes.save(task);
                            } else if (sessionConfig.isResessiontimeout() && agentStatus != null && task.getWarningtime() != null && MainUtils.getLastTime(sessionConfig.getRetimeout()).after(task.getWarningtime())) {    //?????????????????????
                                /**
                                 * ????????????????????? ??????
                                 */
                                processMessage(sessionConfig, sessionConfig.getRetimeoutmsg(), sessionConfig.getServicename(), agentUser, agentStatus, task);
                                try {
                                    AutomaticServiceDist.serviceFinish(agentUser, task.getOrgi());
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                } else if (sessionConfig.isResessiontimeout()) {    //????????????????????????????????????????????????
                    List<AgentUserTask> agentUserTask = agentUserTaskRes.findByLastmessageLessThanAndStatusAndOrgi(MainUtils.getLastTime(sessionConfig.getRetimeout()), MainContext.AgentUserStatusEnum.INSERVICE.toString(), sessionConfig.getOrgi());
                    for (AgentUserTask task : agentUserTask) {        // ???????????????
                        AgentUser agentUser = (AgentUser) CacheHelper.getAgentUserCacheBean().getCacheObject(task.getUserid(), MainContext.SYSTEM_ORGI);
                        if (agentUser != null) {
                            AgentStatus agentStatus = (AgentStatus) CacheHelper.getAgentStatusCacheBean().getCacheObject(agentUser.getAgentno(), task.getOrgi());
                            if (agentStatus != null && task.getWarningtime() != null && MainUtils.getLastTime(sessionConfig.getRetimeout()).after(task.getWarningtime())) {    //?????????????????????
                                /**
                                 * ????????????????????? ??????
                                 */
                                processMessage(sessionConfig, sessionConfig.getRetimeoutmsg(), agentStatus.getUsername(), agentUser, agentStatus, task);
                                try {
                                    AutomaticServiceDist.serviceFinish(agentUser, task.getOrgi());
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                }
                if (sessionConfig.isQuene()) {    //???????????????????????????????????????
                    List<AgentUserTask> agentUserTask = agentUserTaskRes.findByLogindateLessThanAndStatusAndOrgi(MainUtils.getLastTime(sessionConfig.getQuenetimeout()), MainContext.AgentUserStatusEnum.INQUENE.toString(), sessionConfig.getOrgi());
                    for (AgentUserTask task : agentUserTask) {        // ???????????????
                        AgentUser agentUser = (AgentUser) CacheHelper.getAgentUserCacheBean().getCacheObject(task.getUserid(), MainContext.SYSTEM_ORGI);
                        if (agentUser != null) {
                            /**
                             * ??????????????? ??????
                             */
                            processMessage(sessionConfig, sessionConfig.getQuenetimeoutmsg(), sessionConfig.getServicename(), agentUser, null, task);
                            try {
                                AutomaticServiceDist.serviceFinish(agentUser, task.getOrgi());
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }
    }

    @Scheduled(fixedDelay = 5000) // ???5???????????????
    public void agent() {
        List<SessionConfig> sessionConfigList = AutomaticServiceDist.initSessionConfigList();
        if (sessionConfigList != null && sessionConfigList.size() > 0 && MainContext.getContext() != null) {
            for (SessionConfig sessionConfig : sessionConfigList) {
                sessionConfig = AutomaticServiceDist.initSessionConfig(sessionConfig.getOrgi());
                if (sessionConfig != null && MainContext.getContext() != null && sessionConfig.isAgentreplaytimeout()) {
                    List<AgentUserTask> agentUserTask = agentUserTaskRes.findByLastgetmessageLessThanAndStatusAndOrgi(MainUtils.getLastTime(sessionConfig.getAgenttimeout()), MainContext.AgentUserStatusEnum.INSERVICE.toString(), sessionConfig.getOrgi());
                    for (AgentUserTask task : agentUserTask) {        // ???????????????
                        AgentUser agentUser = (AgentUser) CacheHelper.getAgentUserCacheBean().getCacheObject(task.getUserid(), MainContext.SYSTEM_ORGI);
                        if (agentUser != null) {
                            AgentStatus agentStatus = (AgentStatus) CacheHelper.getAgentStatusCacheBean().getCacheObject(agentUser.getAgentno(), task.getOrgi());
                            if (agentStatus != null && ((task.getReptimes() != null && task.getReptimes().equals("0")) || task.getReptimes() == null)) {
                                task.setReptimes("1");
                                task.setReptime(new Date());

                                //??????????????????
                                processMessage(sessionConfig, sessionConfig.getAgenttimeoutmsg(), sessionConfig.getServicename(), agentUser, agentStatus, task);
                                agentUserTaskRes.save(task);
                            }
                        }
                    }
                }
            }
        }
    }

    @Scheduled(fixedDelay = 600000) // ?????????????????????
    public void onlineuser() {
        Page<OnlineUser> pages = onlineUserRes.findByStatusAndCreatetimeLessThan(MainContext.OnlineUserOperatorStatus.ONLINE.toString(), MainUtils.getLastTime(60), new PageRequest(0, 100));
        if (pages.getContent().size() > 0) {
            for (OnlineUser onlineUser : pages.getContent()) {
                try {
                    OnlineUserUtils.offline(onlineUser);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Scheduled(fixedDelay = 10000) // ???10??????????????? ??? ?????????????????????????????????????????? ?????????
    public void traceOnlineUser() {
        if (MainContext.getContext() != null) {    //??????????????????????????????????????? ????????????????????????????????? ??????
            long onlineusers = CacheHelper.getOnlineUserCacheBean().getSize();
            if (onlineusers > 0) {
                Collection<?> datas = CacheHelper.getOnlineUserCacheBean().getAllCacheObject(MainContext.SYSTEM_ORGI);
                ConsultInviteRepository consultInviteRes = MainContext.getContext().getBean(ConsultInviteRepository.class);
                for (Object key : datas) {
                    Object data = CacheHelper.getOnlineUserCacheBean().getCacheObject(key.toString(), MainContext.SYSTEM_ORGI);
                    if (data instanceof OnlineUser) {
                        OnlineUser onlineUser = (OnlineUser) data;
                        if (onlineUser.getUpdatetime() != null
                                && (System.currentTimeMillis() - onlineUser.getUpdatetime().getTime()) < 15000) {
                            OnlineUserRepository service = (OnlineUserRepository) MainContext.getContext().getBean(OnlineUserRepository.class);
                            if (onlineUser.getAppid() != null) { // save with cousult and appId
                                CousultInvite invite = OnlineUserUtils.cousult(onlineUser.getAppid(), onlineUser.getOrgi(), consultInviteRes);
                                if (!invite.isTraceuser()) {
                                    List<OnlineUser> onlineUserList = service.findByUseridAndOrgi(onlineUser.getUserid(), onlineUser.getOrgi());
                                    if (onlineUserList.size() > 1) {
                                        service.delete(onlineUserList);
                                    } else if (onlineUserList.size() == 1) {
                                        OnlineUser tempOnlineUser = onlineUserList.get(0);
                                        onlineUser.setId(tempOnlineUser.getId());
                                    }
                                    service.save(onlineUser);
                                }
                            } else { // appId is not available.
                                service.save(onlineUser);
                            }
                        }
                    } else if (data instanceof AiUser) {
                        AiUser aiUser = (AiUser) data;
                        if (MainContext.model.get("xiaoe") != null) {
                            DataExchangeInterface dataInterface = (DataExchangeInterface) MainContext.getContext().getBean("aiconfig");
                            AiConfig aiConfig = (AiConfig) dataInterface.getDataByIdAndOrgi(aiUser.getAiid(), aiUser.getOrgi());
                            if (aiConfig != null) {
                                long leavetime = (System.currentTimeMillis() - aiUser.getTime()) / 1000;
                                if (aiConfig.getAsktimes() > 0 && leavetime > aiConfig.getAsktimes()) {//??????????????????????????????540???
                                    NettyClients.getInstance().closeIMEventClient(aiUser.getUserid(), aiUser.getId(), MainContext.SYSTEM_ORGI);
                                }
                            }
                        } else {
                            NettyClients.getInstance().closeIMEventClient(aiUser.getUserid(), aiUser.getId(), MainContext.SYSTEM_ORGI);
                        }
                    }
                }
            }
        }
    }

    /**
     * appid : appid ,
     * userid:userid,
     * sign:session,
     * touser:touser,
     * session: session ,
     * orgi:orgi,
     * username:agentstatus,
     * nickname:agentstatus,
     * message : message
     *
     * @param sessionConfig
     * @param agentUser
     * @param task
     */

    private void processMessage(SessionConfig sessionConfig, String message, String servicename, AgentUser agentUser, AgentStatus agentStatus, AgentUserTask task) {

        MessageOutContent outMessage = new MessageOutContent();
        if (StringUtils.isNotBlank(message)) {
            outMessage.setMessage(message);
            outMessage.setMessageType(MainContext.MediaTypeEnum.TEXT.toString());
            outMessage.setCalltype(MainContext.CallTypeEnum.OUT.toString());
            outMessage.setAgentUser(agentUser);
            outMessage.setSnsAccount(null);

            ChatMessage data = new ChatMessage();
            if (agentUser != null) {
                data.setAppid(agentUser.getAppid());

                data.setUserid(agentUser.getUserid());
                data.setUsession(agentUser.getUserid());
                data.setTouser(agentUser.getUserid());
                data.setOrgi(agentUser.getOrgi());
                data.setUsername(agentUser.getUsername());
                data.setMessage(message);

                data.setId(MainUtils.getUUID());
                data.setContextid(agentUser.getContextid());

                data.setAgentserviceid(agentUser.getAgentserviceid());

                data.setCalltype(MainContext.CallTypeEnum.OUT.toString());
                if (StringUtils.isNotBlank(agentUser.getAgentno())) {
                    data.setTouser(agentUser.getUserid());
                }
                data.setChannel(agentUser.getChannel());

                data.setUsession(agentUser.getUserid());

                outMessage.setContextid(agentUser.getContextid());
                outMessage.setFromUser(data.getUserid());
                outMessage.setToUser(data.getTouser());
                outMessage.setChannelMessage(data);
                if (agentStatus != null) {
                    data.setUsername(agentStatus.getUsername());
                    outMessage.setNickName(agentStatus.getUsername());
                } else {
                    data.setUsername(servicename);
                    outMessage.setNickName(servicename);
                }
                outMessage.setCreatetime(data.getCreatetime());

                /**
                 * ????????????
                 */
                MainContext.getContext().getBean(ChatMessageRepository.class).save(data);

                // ???????????????????????????
                if (agentUser != null && StringUtils.isNotBlank(agentUser.getAgentno())) {
                    NettyClients.getInstance().publishAgentEventMessage(agentUser.getAgentno(), MainContext.MessageTypeEnum.MESSAGE.toString(), data);
                }

                if (StringUtils.isNotBlank(data.getTouser())) {
                    OutMessageRouter router = null;
                    router = (OutMessageRouter) MainContext.getContext().getBean(agentUser.getChannel());
                    if (router != null) {
                        router.handler(data.getTouser(), MainContext.MessageTypeEnum.MESSAGE.toString(), agentUser.getAppid(), outMessage);
                    }
                }

            }
        }
    }


    @Scheduled(fixedDelay = 3000) // ????????? , ?????? ?????????????????????????????? ??????????????? ????????????
    public void jobDetail() {
        List<JobDetail> allJob = new ArrayList<JobDetail>();
        Page<JobDetail> readyTaskList = jobDetailRes.findByTaskstatus(MainContext.TaskStatusType.READ.getType(), new PageRequest(0, 100));
        allJob.addAll(readyTaskList.getContent());
        Page<JobDetail> planTaskList = jobDetailRes.findByPlantaskAndTaskstatusAndNextfiretimeLessThan(true, MainContext.TaskStatusType.NORMAL.getType(), new Date(), new PageRequest(0, 100));
        allJob.addAll(planTaskList.getContent());
        if (allJob.size() > 0) {
            for (JobDetail jobDetail : allJob) {
                if (CacheHelper.getJobCacheBean().getCacheObject(jobDetail.getId(), jobDetail.getOrgi()) == null) {
                    jobDetail.setTaskstatus(MainContext.TaskStatusType.QUEUE.getType());
                    jobDetailRes.save(jobDetail);
                    CacheHelper.getJobCacheBean().put(jobDetail.getId(), jobDetail, jobDetail.getOrgi());
                    /**
                     * ???????????????????????????
                     */
                    webimTaskExecutor.execute(new Task(jobDetail, jobDetailRes));
                }
            }
        }
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 20000) // ????????? , ?????? ?????????????????????????????? ??????????????? ????????????
    public void callOut() {
        if (MainContext.model.get("sales") != null) {
            /**
             * ?????? ????????? ???????????? ??????
             */
            List<CallCenterAgent> agents = CallOutQuene.service();
            for (CallCenterAgent agent : agents) {
                webimTaskExecutor.execute(new NamesTask(agent));
            }
        }
    }
}
