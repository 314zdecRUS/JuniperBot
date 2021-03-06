/*
 * This file is part of JuniperBot.
 *
 * JuniperBot is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * JuniperBot is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with JuniperBot. If not, see <http://www.gnu.org/licenses/>.
 */
package ru.juniperbot.api.controller.pub;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.vk.api.sdk.callback.objects.messages.CallbackMessage;
import com.vk.api.sdk.callback.objects.messages.CallbackMessageType;
import com.vk.api.sdk.objects.wall.Wallpost;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import ru.juniperbot.api.controller.base.BasePublicRestController;
import ru.juniperbot.api.model.AtomFeed;
import ru.juniperbot.api.subscriptions.integrations.VkSubscriptionService;
import ru.juniperbot.api.subscriptions.integrations.YouTubeSubscriptionService;
import ru.juniperbot.api.utils.FeedUtils;
import ru.juniperbot.api.utils.IdentityRateLimiter;
import ru.juniperbot.common.model.exception.AccessDeniedException;
import ru.juniperbot.common.model.request.PatreonRequest;
import ru.juniperbot.common.persistence.entity.VkConnection;
import ru.juniperbot.common.utils.GsonUtils;

import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RestController
public class CallbackController extends BasePublicRestController {

    private final Gson gson = GsonUtils.create();

    private final IdentityRateLimiter<String> vkRateLimiter = new IdentityRateLimiter<>(5.0 / 60.0); // Five VK group events per minute

    private final IdentityRateLimiter<String> youTubeRateLimiter = new IdentityRateLimiter<>(1.0 / 60.0); // One YT channel video per minute

    private final static Map<String, Type> CALLBACK_TYPES = Map.of(
            CallbackMessageType.WALL_POST_NEW.getValue(),
            new TypeToken<CallbackMessage<Wallpost>>() {
            }.getType(),
            CallbackMessageType.CONFIRMATION.getValue(),
            new TypeToken<CallbackMessage>() {
            }.getType());

    @Autowired
    private VkSubscriptionService vkSubscriptionService;

    @Autowired
    private YouTubeSubscriptionService youTubeService;

    @SuppressWarnings("unchecked")
    @RequestMapping(value = "/vk/callback/{token}", method = RequestMethod.POST)
    @Transactional
    public String vkCallback(@RequestBody String content, @PathVariable("token") String token, HttpServletResponse response) {
        JsonObject json = gson.fromJson(content, JsonObject.class);
        String type = json.get("type").getAsString();
        Type typeOfClass = CALLBACK_TYPES.get(type);

        VkConnection connection = vkSubscriptionService.getForToken(token);
        if (connection == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }

        if (typeOfClass != null) {
            CallbackMessage message = gson.fromJson(json, typeOfClass);

            if (!CallbackMessageType.CONFIRMATION.equals(message.getType()) &&
                    !connection.getGroupId().equals(message.getGroupId())) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return null;
            }

            switch (message.getType()) {
                case CONFIRMATION:
                    return vkSubscriptionService.confirm(connection, message);
                case WALL_POST_NEW:
                    if (vkRateLimiter.tryAcquire(token)) {
                        vkSubscriptionService.post(connection, message);
                    }
                    break;
            }
        }
        return "ok";
    }

    @RequestMapping(value = "/youtube/callback/publish", method = RequestMethod.POST)
    public void youTubeCallback(
            @AtomFeed SyndFeed feed,
            @RequestParam("secret") String secret) {
        if (!Objects.equals(apiProperties.getYouTube().getPubSubSecret(), secret)) {
            throw new AccessDeniedException();
        }
        taskExecutor.execute(() -> {
            if (CollectionUtils.isEmpty(feed.getEntries())) {
                log.warn("Empty YouTube callback");
                return;
            }
            SyndEntry entry = feed.getEntries().get(0);
            String channelId = FeedUtils.getForeignValue(entry, "channelId");
            String videoId = FeedUtils.getForeignValue(entry, "videoId");
            if (StringUtils.isEmpty(channelId)) {
                log.warn("No channelId found in YouTube callback");
                return;
            }
            if (StringUtils.isEmpty(videoId)) {
                log.warn("No videoId found in YouTube callback");
                return;
            }
            if (youTubeRateLimiter.tryAcquire(channelId)) {
                log.info("Notify YouTube Video[channelId={}, videoId={}]", channelId, videoId);
                youTubeService.notifyVideo(channelId, videoId);
            }
        });
    }

    @RequestMapping(value = "/youtube/callback/publish", method = RequestMethod.GET)
    public String youTubeCallbackChallenge(@RequestParam("hub.verify_token") String secret,
                                           @RequestParam("hub.challenge") String challenge,
                                           @RequestParam(name = "channel", required = false) String channelId) {
        if (!Objects.equals(apiProperties.getYouTube().getPubSubSecret(), secret)) {
            log.warn("YouTube callback challenge denied, wrong secret");
            throw new AccessDeniedException();
        }
        if (channelId != null) {
            youTubeService.prolongChannel(channelId);
        }
        log.info("YouTube callback challenge accepted");
        return challenge;
    }

    @RequestMapping(value = "/patreon/callback", method = RequestMethod.POST)
    public void patreonCallback(@RequestBody String content,
                                @RequestHeader("X-Patreon-Event") String event,
                                @RequestHeader("X-Patreon-Signature") String signature) {
        gatewayService.sendPatreonUpdate(new PatreonRequest(content, event, signature));
    }
}
