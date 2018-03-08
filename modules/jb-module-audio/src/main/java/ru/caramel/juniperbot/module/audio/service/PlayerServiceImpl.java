/*
 * This file is part of JuniperBotJ.
 *
 * JuniperBotJ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * JuniperBotJ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with JuniperBotJ. If not, see <http://www.gnu.org/licenses/>.
 */
package ru.caramel.juniperbot.module.audio.service;

import com.codahale.metrics.annotation.Gauge;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import lombok.Getter;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.VoiceChannel;
import net.dv8tion.jda.core.exceptions.InsufficientPermissionException;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.caramel.juniperbot.core.model.exception.DiscordException;
import ru.caramel.juniperbot.core.persistence.entity.GuildConfig;
import ru.caramel.juniperbot.core.persistence.entity.LocalMember;
import ru.caramel.juniperbot.core.service.ConfigService;
import ru.caramel.juniperbot.core.service.ContextService;
import ru.caramel.juniperbot.core.service.DiscordService;
import ru.caramel.juniperbot.core.service.MemberService;
import ru.caramel.juniperbot.core.support.ModuleListener;
import ru.caramel.juniperbot.module.audio.model.EndReason;
import ru.caramel.juniperbot.module.audio.model.PlaybackInstance;
import ru.caramel.juniperbot.module.audio.model.RepeatMode;
import ru.caramel.juniperbot.module.audio.model.TrackRequest;
import ru.caramel.juniperbot.module.audio.persistence.entity.MusicConfig;
import ru.caramel.juniperbot.module.audio.persistence.entity.Playlist;
import ru.caramel.juniperbot.module.audio.persistence.entity.PlaylistItem;
import ru.caramel.juniperbot.module.audio.persistence.repository.MusicConfigRepository;
import ru.caramel.juniperbot.module.audio.persistence.repository.PlaylistItemRepository;
import ru.caramel.juniperbot.module.audio.persistence.repository.PlaylistRepository;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class PlayerServiceImpl extends AudioEventAdapter implements PlayerService, ModuleListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerServiceImpl.class);

    private final static long TIMEOUT = 180000; // 3 minutes

    @Autowired
    private AudioMessageManager messageManager;

    @Autowired
    private DiscordService discordService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private MusicConfigRepository musicConfigRepository;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private PlaylistItemRepository playlistItemRepository;

    @Autowired
    private ContextService contextService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private List<AudioSourceManager> audioSourceManagers;

    @Autowired
    @Qualifier("executor")
    private TaskExecutor taskExecutor;

    @Getter
    private AudioPlayerManager playerManager;

    private final Map<Long, PlaybackInstance> instances = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        playerManager = new DefaultAudioPlayerManager();
        if (CollectionUtils.isNotEmpty(audioSourceManagers)) {
            audioSourceManagers.forEach(playerManager::registerSourceManager);
        }
        playerManager.getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.MEDIUM);
        playerManager.setFrameBufferDuration((int) TimeUnit.SECONDS.toMillis(2));
        playerManager.setItemLoaderThreadPoolSize(500);
        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioSourceManagers.registerLocalSource(playerManager);
    }

    @Override
    public void onShutdown() {
        instances.values().forEach(e -> {
            if (e.getCurrent() != null) {
                e.getCurrent().setEndReason(EndReason.SHUTDOWN);
            }
            e.stop();
        });
        playerManager.shutdown();
    }

    @Override
    @Transactional
    public MusicConfig getConfig(long serverId) {
        MusicConfig config = musicConfigRepository.findByGuildId(serverId);
        if (config == null) {
            GuildConfig guildConfig = configService.getOrCreate(serverId);
            config = new MusicConfig();
            config.setGuildConfig(guildConfig);
            config.setVoiceVolume(100);
            musicConfigRepository.save(config);
        }
        return config;
    }

    @Override
    @Transactional
    public MusicConfig getConfig(Guild guild) {
        return getConfig(guild.getIdLong());
    }

    @Override
    public PlaybackInstance getInstance(Guild guild) {
        return getInstance(guild.getIdLong(), true);
    }

    @Override
    public PlaybackInstance getInstance(long guildId, boolean create) {
        synchronized (instances) {
            PlaybackInstance instance = instances.get(guildId);
            if (instance == null && create) {
                MusicConfig config = getConfig(guildId);
                instance = instances.computeIfAbsent(guildId, e -> {
                    AudioPlayer player = playerManager.createPlayer();
                    player.addListener(this);
                    player.setVolume(config.getVoiceVolume());
                    return new PlaybackInstance(e, player);
                });
            }
            return instance;
        }
    }

    @Override
    public Map<Long, PlaybackInstance> getInstances() {
        return Collections.unmodifiableMap(instances);
    }

    @Override
    @Transactional
    public void play(List<TrackRequest> requests) throws DiscordException {
        if (CollectionUtils.isEmpty(requests)) {
            return;
        }
        TrackRequest request = requests.get(0);
        PlaybackInstance instance = getInstance(request.getChannel().getGuild());
        storeToPlaylist(instance, requests);
        play(request, instance);
        if (requests.size() > 1) {
            requests.subList(1, requests.size()).forEach(instance::offer);
        }
    }

    @Override
    @Transactional
    public void play(TrackRequest request) throws DiscordException {
        PlaybackInstance instance = getInstance(request.getChannel().getGuild());
        storeToPlaylist(instance, Collections.singletonList(request));
        play(request, instance);
    }

    private void storeToPlaylist(PlaybackInstance instance, List<TrackRequest> requests) {
        if (CollectionUtils.isEmpty(requests)) {
            return;
        }
        LocalMember localMember = memberService.getOrCreate(requests.get(0).getMember());

        synchronized (instance) {
            try {
                Playlist playlist = getPlaylist(instance);
                for (TrackRequest request : requests) {
                    PlaylistItem item = new PlaylistItem(request.getTrack(), localMember);
                    item.setPlaylist(playlist);
                    playlist.getItems().add(item);
                }
                playlistRepository.save(playlist);
            } catch (Exception e) {
                LOGGER.warn("[store] Could not update playlist", e);
            }
        }
    }

    private Playlist getPlaylist(PlaybackInstance instance) {
        Playlist playlist = null;
        if (instance.getPlaylistId() != null) {
            playlist = playlistRepository.findOne(instance.getPlaylistId());
        }
        if (playlist == null) {
            playlist = new Playlist();
            playlist.setUuid(String.valueOf(UUID.randomUUID()).toLowerCase());
            playlist.setItems(new ArrayList<>());
            playlist.setDate(new Date());
            playlist.setGuildConfig(configService.getOrCreate(instance.getGuildId()));
            playlistRepository.save(playlist);
            instance.setPlaylistId(playlist.getId());
            instance.setPlaylistUuid(playlist.getUuid());
        }
        return playlist;
    }

    private void play(TrackRequest request, PlaybackInstance instance) throws DiscordException {
        contextService.withContext(request.getGuild(), () -> messageManager.onTrackAdd(request, instance.getCursor() < 0));
        connectToChannel(instance, request.getMember());
        instance.play(request);
    }

    @Override
    public VoiceChannel connectToChannel(PlaybackInstance instance, Member member) throws DiscordException {
        VoiceChannel channel = getDesiredChannel(member);
        if (channel == null) {
            return null;
        }
        if (!channel.getGuild().getSelfMember().hasPermission(channel, Permission.VOICE_CONNECT)) {
            throw new DiscordException("discord.global.voice.noAccess");
        }
        try {
            instance.openAudioConnection(channel);
        } catch (InsufficientPermissionException e) {
            throw new DiscordException("discord.global.voice.noAccess", e);
        }
        return channel;
    }

    @Override
    public void reconnectAll() {
        instances.forEach((k, v) -> {
            if (v.getCurrent() != null) {
                try {
                    connectToChannel(v, v.getCurrent().getMember());
                } catch (DiscordException e) {
                    // fall down
                }
            }
        });
    }

    @Override
    public void skipTrack(Member member, Guild guild) {
        PlaybackInstance instance = getInstance(guild);
        // сбросим режим если принудительно вызвали следующий
        if (RepeatMode.CURRENT.equals(instance.getMode())) {
            instance.setMode(RepeatMode.NONE);
        }
        if (instance.getCurrent() != null) {
            instance.getCurrent().setEndReason(EndReason.SKIPPED);
            instance.getCurrent().setEndMember(member);
        }
        onTrackEnd(instance.getPlayer(), instance.getPlayer().getPlayingTrack(), AudioTrackEndReason.FINISHED);
    }

    @Override
    @Transactional
    public TrackRequest removeByIndex(Guild guild, int index) {
        PlaybackInstance instance = getInstance(guild);
        TrackRequest result = instance.removeByIndex(index);
        if (result != null && instance.getPlaylistId() != null) {
            refreshStoredPlaylist(instance);
        }
        return result;
    }

    @Override
    @Transactional
    public boolean shuffle(Guild guild) {
        PlaybackInstance instance = getInstance(guild);
        boolean result;
        synchronized (instance) {
            result = instance.shuffle();
            if (result) {
                refreshStoredPlaylist(instance);
            }
        }
        return result;
    }

    private void refreshStoredPlaylist(PlaybackInstance instance) {
        try {
            Playlist playlist = getPlaylist(instance);
            List<PlaylistItem> toRemove = new ArrayList<>(playlist.getItems());
            List<PlaylistItem> newItems = new ArrayList<>(playlist.getItems().size());
            instance.getPlaylist().forEach(e -> {
                PlaylistItem item = find(playlist, e.getTrack().getInfo());
                if (item == null) {
                    LocalMember member = memberService.getOrCreate(e.getMember());
                    item = new PlaylistItem(e.getTrack(), member);
                }
                newItems.add(item);
            });
            toRemove.removeAll(newItems);
            playlist.setItems(newItems);
            playlistRepository.save(playlist);
            if (!toRemove.isEmpty()) {
                playlistItemRepository.delete(toRemove);
            }
        } catch (Exception e) {
            LOGGER.warn("[shuffle] Could not update playlist", e);
        }
    }

    @Override
    public boolean isInChannel(Member member) {
        PlaybackInstance instance = getInstance(member.getGuild());
        VoiceChannel channel = getChannel(member, instance);
        return channel != null && channel.getMembers().contains(member);
    }

    @Override
    public boolean hasAccess(Member member) {
        MusicConfig config = getConfig(member.getGuild());
        return config == null
                || CollectionUtils.isEmpty(config.getRoles())
                || member.isOwner()
                || member.hasPermission(Permission.ADMINISTRATOR)
                || member.getRoles().stream().anyMatch(e -> config.getRoles().contains(e.getIdLong()));
    }

    private VoiceChannel getDesiredChannel(Member member) {
        MusicConfig musicConfig = getConfig(member.getGuild());
        VoiceChannel channel = null;
        if (musicConfig != null) {
            if (musicConfig.isUserJoinEnabled() && member.getVoiceState().inVoiceChannel()) {
                channel = member.getVoiceState().getChannel();
            }
            if (channel == null && musicConfig.getChannelId() != null) {
                channel = discordService.getShardManager().getVoiceChannelById(musicConfig.getChannelId());
            }
        }
        if (channel == null) {
            channel = discordService.getDefaultMusicChannel(member.getGuild().getIdLong());
        }
        return channel;
    }

    @Override
    public VoiceChannel getChannel(Member member) {
        PlaybackInstance instance = getInstance(member.getGuild());
        return getChannel(member, instance);
    }

    private VoiceChannel getChannel(Member member, PlaybackInstance instance) {
        return instance.isActive() ? instance.getAudioManager().getConnectedChannel() : getDesiredChannel(member);
    }

    private long countListeners(PlaybackInstance instance) {
        if (instance.isActive()) {
            return instance.getAudioManager().getConnectedChannel().getMembers()
                    .stream()
                    .filter(e -> !e.getUser().equals(e.getJDA().getSelfUser())).count();
        }
        return 0;
    }

    @Scheduled(fixedDelay = 15000)
    public void monitor() {
        long currentTimeMillis = System.currentTimeMillis();

        Set<Long> inactiveIds = new HashSet<>();
        instances.forEach((k, v) -> {
            long lastMillis = v.getActiveTime();
            TrackRequest current = v.getCurrent();
            if (!discordService.isConnected(v.getGuildId()) || countListeners(v) > 0) {
                v.setActiveTime(currentTimeMillis);
                return;
            }
            if (currentTimeMillis - lastMillis > TIMEOUT) {
                if (current != null) {
                    contextService.withContext(current.getGuild(), () -> messageManager.onIdle(current.getChannel()));
                }
                v.stop();
                v.getPlayer().removeListener(this);
                inactiveIds.add(k);
            }
        });
        inactiveIds.forEach(instances::remove);
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (track == null) {
            return;
        }
        PlaybackInstance instance = track.getUserData(PlaybackInstance.class);
        if (instance == null) {
            return;
        }
        TrackRequest current = instance.getCurrent();
        if (current != null) {
            if (current.getEndReason() == null) {
                current.setEndReason(EndReason.getForLavaPlayer(endReason));
            }
            contextService.withContext(current.getGuild(), () -> messageManager.onTrackEnd(current));
        }
        switch (endReason) {
            case STOPPED:
            case CLEANUP:
                break;
            case REPLACED:
                return;
            case FINISHED:
            case LOAD_FAILED:
                if (instance.playNext()) {
                    return;
                }
                if (current != null) {
                    contextService.withContext(instance.getGuildId(), () -> messageManager.onQueueEnd(current));
                }
                break;
        }
        musicConfigRepository.updateVolume(instance.getGuildId(), instance.getPlayer().getVolume());
        // execute instance reset out of current thread
        taskExecutor.execute(() -> {
            instance.reset();
            instance.getPlayer().removeListener(this);
            instances.remove(instance.getGuildId());
        });
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        PlaybackInstance instance = track.getUserData(PlaybackInstance.class);
        contextService.withContext(instance.getGuildId(), () -> messageManager.onTrackStart(instance.getCurrent()));
    }

    @Override
    public void onPlayerPause(AudioPlayer player) {
        PlaybackInstance instance = player.getPlayingTrack().getUserData(PlaybackInstance.class);
        if (instance.isActive()) {
            contextService.withContext(instance.getGuildId(), () -> messageManager.onTrackPause(instance.getCurrent()));
        }
    }

    @Override
    public void onPlayerResume(AudioPlayer player) {
        AudioTrack track = player.getPlayingTrack();
        if (track != null) {
            PlaybackInstance instance = track.getUserData(PlaybackInstance.class);
            if (instance.isActive()) {
                contextService.withContext(instance.getGuildId(), () -> messageManager.onTrackResume(instance.getCurrent()));
            }
        }
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        LOGGER.error("Track error", exception);
    }

    @Gauge(name = ACTIVE_CONNECTIONS, absolute = true)
    @Override
    public long getActiveCount() {
        return instances.size();
    }

    @Override
    public boolean stop(Member member, Guild guild) {
        PlaybackInstance instance = instances.get(guild.getIdLong());
        if (instance == null) {
            return false;
        }
        instances.computeIfPresent(guild.getIdLong(), (g, e) -> {
            if (e.isActive() && e.getCurrent() != null) {
                e.getCurrent().setEndReason(EndReason.STOPPED);
                e.getCurrent().setEndMember(member);
            }
            e.stop();
            e.getPlayer().removeListener(this);
            return null;
        });
        instances.remove(guild.getIdLong());
        messageManager.clear(guild);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Playlist getPlaylist(String uuid) {
        return playlistRepository.findByUuid(uuid);
    }

    private static PlaylistItem find(Playlist playlist, AudioTrackInfo info) {
        for (PlaylistItem item : playlist.getItems()) {
            if (item != null &&
                    Objects.equals(item.getTitle(), info.title) &&
                    Objects.equals(item.getAuthor(), info.author) &&
                    Objects.equals(item.getLength(), info.length) &&
                    Objects.equals(item.getIdentifier(), info.identifier) &&
                    Objects.equals(item.getUri(), info.uri)) {
                return item;
            }
        }
        return null;
    }
}