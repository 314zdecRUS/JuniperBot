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
package ru.juniperbot.api.dto;

import lombok.Getter;
import lombok.Setter;
import net.dv8tion.jda.api.Permission;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
public class GuildShortDto implements Serializable {
    private static final long serialVersionUID = 5292170456825288531L;

    private String id;

    private String name;

    private String icon;

    private boolean owner;

    private List<Permission> permissions;

    private boolean added;

    private long members;
}
