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
package ru.juniperbot.api.dto.request;

import lombok.Getter;
import lombok.Setter;
import ru.juniperbot.api.model.SubscriptionType;

import java.io.Serializable;
import java.util.Map;

@Getter
@Setter
public class SubscriptionCreateRequest implements Serializable {

    private static final long serialVersionUID = -9048832861433563477L;

    private SubscriptionType type;

    private Map<String, Object> data;

}
