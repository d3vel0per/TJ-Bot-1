package org.togetherjava.tjbot.commands.moderation.temp;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.RestAction;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.togetherjava.tjbot.commands.moderation.ActionRecord;
import org.togetherjava.tjbot.commands.moderation.ModerationAction;
import org.togetherjava.tjbot.commands.moderation.ModerationActionsStore;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// FIXME javadoc
public final class TemporaryModerationRoutine {
    private static final Logger logger = LoggerFactory.getLogger(TemporaryModerationRoutine.class);

    private final ModerationActionsStore actionsStore;
    private final JDA jda;
    private final ScheduledExecutorService checkExpiredActionsService =
            Executors.newSingleThreadScheduledExecutor();
    private final Map<ModerationAction, RevocableModerationAction> typeToRevocableAction;

    /**
     * Creates a new instance.
     *
     * @param jda the JDA instance to use to send messages and retrieve information
     * @param actionsStore the store used to retrieve temporary moderation actions
     */
    public TemporaryModerationRoutine(@NotNull JDA jda,
            @NotNull ModerationActionsStore actionsStore) {
        this.actionsStore = actionsStore;
        this.jda = jda;

        typeToRevocableAction = Stream.of(new TemporaryBanAction(), new TemporaryMuteAction())
            .collect(
                    Collectors.toMap(RevocableModerationAction::getApplyType, Function.identity()));
    }

    private void checkExpiredActions() {
        logger.debug("Checking expired temporary moderation actions to revoke...");

        actionsStore.getExpiredActionsAscending()
            .stream()
            .filter(action -> typeToRevocableAction.containsKey(action.actionType()))
            .collect(Collectors.groupingBy(RevocationGroupIdentifier::of))
            .forEach(this::processGroupedActions);

        logger.debug("Finished checking expired temporary moderation actions to revoke.");
    }

    private void processGroupedActions(@NotNull RevocationGroupIdentifier groupIdentifier,
            @NotNull Collection<ActionRecord> expiredActions) {
        // Last issued temporary action takes priority
        ActionRecord actionToRevoke = expiredActions.stream()
            .max(Comparator.comparing(ActionRecord::issuedAt))
            .orElseThrow();

        // Do not revoke an action which was overwritten by a permanent action that was issued
        // afterwards
        // For example if a user was perm-banned after being temp-banned
        ActionRecord lastAction = actionsStore
            .findLastActionAgainstTargetByType(groupIdentifier.guildId, groupIdentifier.targetId,
                    groupIdentifier.type)
            .orElseThrow();
        if (lastAction.actionExpiresAt() == null) {
            return;
        }

        // Do not revoke an action which was already revoked by another action issued afterwards
        // For example if a user was unbanned manually after being temp-banned,
        // but also if the system automatically revoked a temp-ban already itself
        ModerationAction revokeActionType =
                getRevocableActionByType(groupIdentifier.type).getRevokeType();
        ActionRecord lastRevokeAction = actionsStore
            .findLastActionAgainstTargetByType(groupIdentifier.guildId, groupIdentifier.targetId,
                    revokeActionType)
            .orElseThrow();
        if (lastRevokeAction.issuedAt().isAfter(actionToRevoke.issuedAt())
                && (lastRevokeAction.actionExpiresAt() == null
                        || lastRevokeAction.actionExpiresAt().isAfter(Instant.now()))) {
            return;
        }

        revokeAction(groupIdentifier);
    }

    private void revokeAction(@NotNull RevocationGroupIdentifier groupIdentifier) {
        Guild guild = jda.getGuildById(groupIdentifier.guildId);
        if (guild == null) {
            logger.info(
                    "Attempted to revoke a temporary moderation action but the bot is not connected to the guild '{}' anymore, skipping revoking.",
                    groupIdentifier.guildId);
            return;
        }

        jda.retrieveUserById(groupIdentifier.targetId)
            .flatMap(target -> executeRevocation(guild, target, groupIdentifier.type))
            .queue(result -> {
            }, failure -> handleFailure(failure, groupIdentifier));
    }

    private @NotNull RestAction<Void> executeRevocation(@NotNull Guild guild, @NotNull User target,
            @NotNull ModerationAction actionType) {
        logger.info("Revoked temporary action {} against user '{}' ({}).", actionType,
                target.getAsTag(), target.getId());

        String reason = "Automatic revocation of temporary action.";
        actionsStore.addAction(guild.getIdLong(), jda.getSelfUser().getIdLong(), target.getIdLong(),
                actionType, null, reason);

        return getRevocableActionByType(actionType).revokeAction(guild, target, reason);
    }

    private void handleFailure(@NotNull Throwable failure,
            @NotNull RevocationGroupIdentifier groupIdentifier) {
        if (getRevocableActionByType(groupIdentifier.type).handleRevokeFailure(failure,
                groupIdentifier.targetId) == RevocableModerationAction.FailureIdentification.KNOWN) {
            return;
        }

        logger.warn(
                "Attempted to revoke a temporary moderation action for user '{}' but something unexpected went wrong.",
                groupIdentifier.targetId, failure);
    }

    private @NotNull RevocableModerationAction getRevocableActionByType(
            @NotNull ModerationAction type) {
        return Objects.requireNonNull(typeToRevocableAction.get(type),
                "Action type is not revocable: " + type);
    }

    /**
     * Starts the routine, automatically checking expired temporary moderation actions on a
     * schedule.
     */
    public void start() {
        // TODO This should be registered at some sort of routine system instead (see GH issue #235
        // which adds support for routines)
        checkExpiredActionsService.scheduleWithFixedDelay(this::checkExpiredActions, 0, 5,
                TimeUnit.MINUTES);
    }

    private record RevocationGroupIdentifier(long guildId, long targetId,
            @NotNull ModerationAction type) {
        public static RevocationGroupIdentifier of(@NotNull ActionRecord actionRecord) {
            return new RevocationGroupIdentifier(actionRecord.guildId(), actionRecord.targetId(),
                    actionRecord.actionType());
        }
    }
}
