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
package ru.juniperbot.module.mafia.service;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.entities.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.juniperbot.common.persistence.entity.GuildConfig;
import ru.juniperbot.module.mafia.model.MafiaInstance;
import ru.juniperbot.module.mafia.model.MafiaPlayer;
import ru.juniperbot.module.mafia.model.MafiaRole;
import ru.juniperbot.module.mafia.model.MafiaState;
import ru.juniperbot.module.mafia.service.base.AbstractStateHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ChoosingHandler extends AbstractStateHandler {

    @Autowired
    private MeetingHandler meetingHandler;

    @Override
    public boolean onStart(User user, MafiaInstance instance) {
        instance.tick();
        String delayText = getEndTimeText(instance, CHOOSING_DELAY);
        EmbedBuilder builder = getBaseEmbed("mafia.start.message");

        GuildConfig config = configService.get(instance.getGuild());
        String nextCommand = messageService.getMessageByLocale("discord.command.mafia.done.key", config.getCommandLocale());

        builder.setFooter(messageService.getMessage("mafia.start.message.footer", delayText, instance.getPrefix(), nextCommand), null);

        TextChannel channel = instance.getChannel();
        if (channel == null) {
            return true; // end for non existent channel instantly
        }

        channel.sendMessage(builder.build()).queue(message -> {
            message.addReaction(CHOOSE).queue();
            instance.getListenedMessages().add(message.getId());
            reactionsListener.onReaction(message.getId(), (event, add) -> {
                if (!instance.isInState(MafiaState.CHOOSING)) {
                    return true;
                }
                String emote = event.getReaction().getReactionEmote().getName();
                if (!event.getUser().equals(event.getJDA().getSelfUser()) && !event.getUser().isBot() && CHOOSE.equals(emote)) {
                    instance.tick();
                    if (add && instance.getPlayers().size() < 10) {
                        instance.getPlayers().add(new MafiaPlayer(event.getMember()));
                    }
                    if (!add) {
                        instance.getPlayers().removeIf(e -> event.getMember().getUser().getIdLong() == e.getMemberId());
                    }
                }
                return false;
            });
        });
        return scheduleEnd(instance, CHOOSING_DELAY);
    }

    @Override
    public boolean onEnd(User user, MafiaInstance instance) {
        instance.setState(MafiaState.MEETING);
        int playerCount = instance.getPlayers().size();
        int minPlayers = DEBUG ? 2 : 3;
        if (playerCount < minPlayers) {
            instance.setEndReason(messageService.getMessage("mafia.start.minPlayers.title", minPlayers));
            return true;
        }
        List<MafiaRole> roleList = getRoles(playerCount);
        for (int i = 0; i < playerCount; i++) {
            MafiaPlayer player = instance.getPlayers().get(i);
            if (player == null) {
                return true;
            }
            player.setRole(roleList.get(i));
        }
        return meetingHandler.onStart(user, instance);
    }

    private List<MafiaRole> getRoles(int playerCount) {
        List<MafiaRole> roleList = new ArrayList<>(playerCount);
        roleList.add(MafiaRole.GOON);
        if (playerCount > 4) {
            roleList.add(MafiaRole.DOCTOR);
            roleList.add(MafiaRole.BROKER);
        }
        if (playerCount > 6) {
            roleList.add(MafiaRole.COP);
        }
        if (playerCount > 7) {
            roleList.add(MafiaRole.GOON);
        }
        while (playerCount > roleList.size()) {
            roleList.add(MafiaRole.TOWNIE);
        }
        Collections.shuffle(roleList);
        return roleList;
    }
}
