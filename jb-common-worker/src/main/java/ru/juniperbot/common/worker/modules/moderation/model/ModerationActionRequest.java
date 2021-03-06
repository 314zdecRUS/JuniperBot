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
package ru.juniperbot.common.worker.modules.moderation.model;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import org.joda.time.DateTime;
import ru.juniperbot.common.model.ModerationActionType;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Getter
@Builder
public class ModerationActionRequest implements Serializable {
    private static final long serialVersionUID = 6374637369757626407L;

    @NonNull
    private ModerationActionType type;

    private Member violator;

    private String violatorId;

    private Member moderator;

    private String moderatorId;

    @Builder.Default
    private boolean auditLogging = true;

    private String reason;

    private TextChannel channel;

    private boolean global;

    private Integer delDays;

    private Long duration;

    private boolean stateless;

    private List<Long> assignRoles;

    private List<Long> revokeRoles;

    public Guild getGuild() {
        if (moderator != null) {
            return moderator.getGuild();
        }
        if (violator != null) {
            return violator.getGuild();
        }
        if (channel != null) {
            return channel.getGuild();
        }
        return null;
    }

    public static class ModerationActionRequestBuilder {

        public ModerationActionRequestBuilder moderator(Member moderator) {
            ModerationActionRequestBuilder.this.moderator = moderator;
            ModerationActionRequestBuilder.this.moderatorId = moderator != null ? moderator.getId() : null;
            return this;
        }

        public ModerationActionRequestBuilder violator(Member violator) {
            ModerationActionRequestBuilder.this.violator = violator;
            ModerationActionRequestBuilder.this.violatorId = violator != null ? violator.getId() : null;
            return this;
        }
    }

    public Date getDurationDate() {
        DateTime now = DateTime.now();
        return duration != null ? now.plus(duration).toDate() : null;
    }
}
