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
package com.chatopera.cc.app.handler.api.rest;

import com.chatopera.cc.app.basic.MainContext;
import com.chatopera.cc.util.Constants;
import com.chatopera.cc.util.Menu;
import com.chatopera.cc.exception.CallOutRuntimeException;
import com.chatopera.cc.app.persistence.repository.CallOutDialplanRepository;
import com.chatopera.cc.app.persistence.repository.UserRepository;
import com.chatopera.cc.app.schedule.CallOutPlanTask;
import com.chatopera.cc.app.handler.Handler;
import com.chatopera.cc.app.handler.api.request.RestUtils;
import com.chatopera.cc.app.model.CallOutDialplan;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/callout/dialplan")
@Api(value = "??????????????????", description = "?????????????????????????????????")
public class ApiDailplanRunController extends Handler {
    private final static Logger logger = LoggerFactory.getLogger(ApiDailplanRunController.class);
    private HashOperations<String, String, String> redisHashOps;

    @Autowired
    private UserRepository userRes;

    @Autowired
    private CallOutDialplanRepository callOutDialplanRes;

    @Autowired
    private CallOutPlanTask callOutPlanTask;

    @Autowired
    private StringRedisTemplate redis;

    @PostConstruct
    private void init() {
        redisHashOps = redis.opsForHash();
    }


    /**
     * ????????????ID??????Sips????????????
     *
     * @param organ
     * @param orgi
     * @return
     */
    private JsonArray getSipsByOrgan(final String organ, final String orgi) {
        logger.info("[callout executor] getSipsByOrgan {}", organ);
        JsonArray j = new JsonArray();
        List<String> sips = userRes.findSipsByOrganAndDatastatusAndOrgi(organ, false, orgi);
        for (String sip : sips) {
            if (StringUtils.isNotBlank(sip))
                j.add(StringUtils.trim(sip));
        }

        logger.info("[callout executor] sips {}", j.toString());
        return j;
    }

    /**
     * ??????????????????
     *
     * @param dp
     * @return
     */
    protected JsonObject execute(final CallOutDialplan dp) {
        JsonObject resp = new JsonObject();

        if (dp.isIsarchive()) {
            resp.addProperty(RestUtils.RESP_KEY_RC, RestUtils.RESP_RC_FAIL_5);
            resp.addProperty(RestUtils.RESP_KEY_ERROR, String.format("?????????????????????????????????????????????????????????", dp.getStatus()));
        } else if (dp.getStatus().equals(MainContext.CallOutDialplanStatusEnum.STOPPED.toString())) {

            // ?????????????????????SIP????????????
            final JsonArray sips = getSipsByOrgan(dp.getOrgan().getId(), MainContext.SYSTEM_ORGI);

            if (sips.size() == 0) {
                resp.addProperty(RestUtils.RESP_KEY_RC, RestUtils.RESP_RC_FAIL_6);
                resp.addProperty(RestUtils.RESP_KEY_ERROR, "?????????????????????????????????????????????SIP????????????????????????");
                return resp;
            }

            // ????????????????????????????????????????????? X ?????????????????????
            final long countagent = userRes.countByOrgiAndAgentAndDatastatusAndOrgan(MainContext.SYSTEM_ORGI, true, false, dp.getOrgan().getId());
            final int concurrency = (int) Math.ceil(countagent * dp.getConcurrenceratio());
            logger.info("[callout executor] ????????? {}", concurrency);
            if (concurrency >= 1) {
                dp.setExecuted(dp.getExecuted() + 1);
                // ????????????
                dp.setStatus(MainContext.CallOutDialplanStatusEnum.RUNNING.toString());
                dp.setCurconcurrence(concurrency);

                // ??????????????????????????????????????????
                String existed = redisHashOps.get(String.format(Constants.FS_DIALPLAN_STATUS, dp.getVoicechannel().getBaseURL()), dp.getId());
                if (existed != null) {
                    logger.info("[callout api] ?????? Redis??????????????? {}", dp.getName(), existed);
                    JsonParser parser = new JsonParser();
                    JsonObject pre = parser.parse(existed).getAsJsonObject();
                    // ???RUNNING?????????
                    if (pre.has("status") && !(pre.get("status").getAsString().equals(MainContext.CallOutDialplanStatusEnum.RUNNING.toString()))) {
                        logger.info("[callout api] ?????????????????????????????? {} {}", dp.getName(), dp.getId());

                        // ?????????????????????
                        pre.addProperty("concurrency", concurrency);
                        pre.addProperty("status", MainContext.CallOutDialplanStatusEnum.RUNNING.toString());
                        pre.addProperty("updatetime", (new Date()).toString());
                        pre.add("sips", sips);
                        redisHashOps.put(String.format(Constants.FS_DIALPLAN_STATUS, dp.getVoicechannel().getBaseURL()), dp.getId(), pre.toString());

                        // ????????????
                        JsonObject payload = new JsonObject();
                        payload.addProperty("dialplan", dp.getId());
                        payload.addProperty("concurrency", concurrency);
                        payload.addProperty("ops", "start");
                        payload.addProperty("channel", dp.getVoicechannel().getBaseURL());
                        payload.add("sips", sips);
                        callOutPlanTask.publish(String.format(Constants.FS_CHANNEL_CC_TO_FS, dp.getVoicechannel().getBaseURL()), payload.toString());
                    } else {
                        logger.error("[callout api] Redis ???????????????????????????????????????????????????????????????????????? {}", pre.toString());
                    }
                } else {
                    logger.info("[callout api] ???{}??? Redis????????????????????????????????????????????????", dp.getName());
                    try {
                        callOutPlanTask.run(dp, sips);
                    } catch (CallOutRuntimeException e) {
                        logger.error("[callout api] ????????????????????????", e);
                    }
                }
                callOutDialplanRes.save(dp);
                resp.addProperty(RestUtils.RESP_KEY_RC, RestUtils.RESP_RC_SUCC);
                resp.addProperty(RestUtils.RESP_KEY_MSG, "????????????");
            } else {
                resp.addProperty(RestUtils.RESP_KEY_RC, RestUtils.RESP_RC_FAIL_3);
                resp.addProperty(RestUtils.RESP_KEY_ERROR, String.format("???????????????[%s]?????????????????????????????????????????????", dp.getOrgan().getName()));
            }
        } else {
            resp.addProperty(RestUtils.RESP_KEY_RC, RestUtils.RESP_RC_FAIL_4);
            resp.addProperty(RestUtils.RESP_KEY_ERROR, String.format("??????????????????????????? [%s]??????????????????????????????", dp.getStatus()));
        }
        return resp;
    }

    /**
     * ??????????????????????????????
     *
     * @param dp
     * @return
     */
    private JsonObject pause(final CallOutDialplan dp) {
        JsonObject resp = new JsonObject();
        if (dp.getStatus().equals(MainContext.CallOutDialplanStatusEnum.RUNNING.toString())) {
            JsonObject payload = new JsonObject();
            payload.addProperty("dialplan", dp.getId());
            payload.addProperty("ops", "pause");
            payload.addProperty("channel", dp.getVoicechannel().getBaseURL());
            callOutPlanTask.publish(String.format(Constants.FS_CHANNEL_CC_TO_FS, dp.getVoicechannel().getBaseURL()), payload.toString());

            Date dt = new Date();
            JsonObject payload2 = new JsonObject();
            payload2.addProperty("concurrency", dp.getCurconcurrence());
            payload2.addProperty("status", MainContext.CallOutDialplanStatusEnum.STOPPED.toString());
            payload2.addProperty("channel", dp.getVoicechannel().getBaseURL());
            payload2.addProperty("updatetime", dt.toString());
            callOutPlanTask.setHashKeyValue(String.format(Constants.FS_DIALPLAN_STATUS, dp.getVoicechannel().getBaseURL()), dp.getId(), payload2.toString());

            dp.setUpdatetime(dt);
            dp.setStatus(MainContext.CallOutDialplanStatusEnum.STOPPED.toString());
            callOutDialplanRes.save(dp);
            resp.addProperty(RestUtils.RESP_KEY_RC, RestUtils.RESP_RC_SUCC);
            resp.addProperty(RestUtils.RESP_KEY_MSG, "???????????????????????????");
        } else {
            resp.addProperty(RestUtils.RESP_KEY_RC, RestUtils.RESP_RC_FAIL_3);
            resp.addProperty(RestUtils.RESP_KEY_ERROR, "????????????????????????????????????????????????");
        }
        return resp;
    }

    /**
     * ??????????????????
     *
     * @param dp
     * @return
     */
    private JsonObject delete(final CallOutDialplan dp) {
        JsonObject resp = new JsonObject();
        if (!dp.isIsarchive()) {
            // ??????????????????
            JsonObject payload = new JsonObject();
            payload.addProperty("dialplan", dp.getId());
            payload.addProperty("ops", "cancel");
            payload.addProperty("channel", dp.getVoicechannel().getBaseURL());
            callOutPlanTask.publish(String.format(Constants.FS_CHANNEL_CC_TO_FS, dp.getVoicechannel().getBaseURL()), payload.toString());

            // ??????????????????
            callOutPlanTask.delHashKey(String.format(Constants.FS_DIALPLAN_STATUS, dp.getVoicechannel().getBaseURL()), dp.getId());

            // ???????????????
            dp.setStatus(MainContext.CallOutDialplanStatusEnum.STOPPED.toString());
            dp.setIsarchive(true);
            dp.setUpdatetime(new Date());
            callOutDialplanRes.save(dp);

            // ??????????????????
            callOutPlanTask.delKey(String.format(Constants.FS_DIALPLAN_TARGET, dp.getVoicechannel().getBaseURL(), dp.getId()));

            resp.addProperty(RestUtils.RESP_KEY_RC, RestUtils.RESP_RC_SUCC);
            resp.addProperty(RestUtils.RESP_KEY_MSG, "??????????????????????????????");

        } else {
            resp.addProperty(RestUtils.RESP_KEY_RC, RestUtils.RESP_RC_SUCC);
            resp.addProperty(RestUtils.RESP_KEY_MSG, "??????????????????????????????");
        }
        return resp;
    }


    /**
     * ??????????????????
     *
     * @param request
     * @return
     */
    @RequestMapping(method = RequestMethod.POST)
    @Menu(type = "apps", subtype = "callout", access = true)
    @ApiOperation("??????????????????")
    public ResponseEntity<String> execute(HttpServletRequest request, @RequestBody final String body) throws CallOutRuntimeException {
        final JsonObject j = (new JsonParser()).parse(body).getAsJsonObject();
        if (!(j.has("ops") && j.has("dialplanId")))
            throw new CallOutRuntimeException("Invalid body");
        final String ops = StringUtils.trim(j.get("ops").getAsString()).toLowerCase();
        final String dialplanId = StringUtils.trim(j.get("dialplanId").getAsString());
        JsonObject json = new JsonObject();
        HttpHeaders headers = RestUtils.header();

        if (callOutDialplanRes.existsById(dialplanId)) {
            CallOutDialplan dp = callOutDialplanRes.findOne(dialplanId);
            switch (ops) {
                case "execute":
                    json = execute(dp);
                    break;
                case "pause":
                    json = pause(dp);
                    break;
                case "delete":
                    json = delete(dp);
                    break;
                default:
                    json.addProperty(RestUtils.RESP_KEY_RC, RestUtils.RESP_RC_FAIL_1);
                    json.addProperty(RestUtils.RESP_KEY_ERROR, "?????????????????????");
            }
        } else {
            json.addProperty(RestUtils.RESP_KEY_RC, RestUtils.RESP_RC_FAIL_2);
            json.addProperty(RestUtils.RESP_KEY_ERROR, "???????????????????????????");
        }

        return new ResponseEntity<String>(json.toString(), headers, HttpStatus.OK);
    }
}
