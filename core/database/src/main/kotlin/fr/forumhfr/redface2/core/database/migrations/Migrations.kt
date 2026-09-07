package fr.forumhfr.redface2.core.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 (Phase 1D PR 3 — #26):
 *
 * - Adds `authMode` to `topic_pages` and `posts` so an anonymous prefetch row
 *   never overwrites the per-user fields of an authenticated row. Existing
 *   v1 rows were all written by the authenticated client (only one client
 *   existed in v1), so we backfill `'AUTHENTICATED'`.
 * - Creates `flag_topics`: persistence of the user's drapeaux page with
 *   isolation by HFR pseudo (`userId` in the primary key).
 *
 * Why hand-written, not auto: Room's auto-migrations would require splitting
 * the schema into two `@Database` versions and an `AutoMigrationSpec` to set
 * the default for the new column, which is more code than the four DDL
 * statements below. The migration is pure DDL — no row rewrite — so a manual
 * migration is the simpler answer.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE topic_pages ADD COLUMN authMode TEXT NOT NULL DEFAULT 'AUTHENTICATED'",
        )
        db.execSQL(
            "ALTER TABLE posts ADD COLUMN authMode TEXT NOT NULL DEFAULT 'AUTHENTICATED'",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `flag_topics` (
                `userId` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `cat` INTEGER NOT NULL,
                `subcat` INTEGER,
                `topicId` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `totalPages` INTEGER NOT NULL,
                `replyCount` INTEGER NOT NULL,
                `hasUnread` INTEGER NOT NULL,
                `lastReadPage` INTEGER NOT NULL,
                `lastPostReadId` INTEGER,
                `firstPostAuthor` TEXT NOT NULL,
                `lastReplyAuthor` TEXT NOT NULL,
                `lastReplyAt` TEXT NOT NULL,
                `fetchedAt` INTEGER NOT NULL,
                `authMode` TEXT NOT NULL,
                PRIMARY KEY(`userId`, `type`, `cat`, `topicId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_flag_topics_userId_type` " +
                "ON `flag_topics` (`userId`, `type`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_flag_topics_userId_fetchedAt` " +
                "ON `flag_topics` (`userId`, `fetchedAt`)",
        )
    }
}

/**
 * v2 → v3 (Phase 1D hotfix):
 *
 * Rebuilds `flag_topics` to match the REST-aligned shape of [FlagTopicEntity] :
 * - drops `views NOT NULL` (the REST API does not expose a view count),
 * - replaces `firstUnreadPostId INTEGER NOT NULL` with `lastPostReadId INTEGER`
 *   (REST exposes `last_post_read_id`, the **last** post the user has read,
 *   nullable when omitted on a row).
 *
 * Devices that ran an intermediate Phase 1D AAB (v25-v28) wrote `flag_topics`
 * with the legacy shape, then upgraded to a build whose entity expects the new
 * shape — Room then fails with `IllegalStateException: Room cannot verify the
 * data integrity` because the identity hash changed without a version bump.
 * This migration drops the table and recreates it: `flag_topics` is pure cache
 * (drapeaux are refetched at next observe), so destructive recreation is safe
 * and avoids a column-by-column rewrite that would gain nothing for empty rows.
 *
 * `topic_pages` and `posts` are untouched : their schema didn't change between
 * v2 and v3, only `flag_topics` did.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `flag_topics`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `flag_topics` (
                `userId` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `cat` INTEGER NOT NULL,
                `subcat` INTEGER,
                `topicId` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `totalPages` INTEGER NOT NULL,
                `replyCount` INTEGER NOT NULL,
                `hasUnread` INTEGER NOT NULL,
                `lastReadPage` INTEGER NOT NULL,
                `lastPostReadId` INTEGER,
                `firstPostAuthor` TEXT NOT NULL,
                `lastReplyAuthor` TEXT NOT NULL,
                `lastReplyAt` TEXT NOT NULL,
                `fetchedAt` INTEGER NOT NULL,
                `authMode` TEXT NOT NULL,
                PRIMARY KEY(`userId`, `type`, `cat`, `topicId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_flag_topics_userId_type` " +
                "ON `flag_topics` (`userId`, `type`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_flag_topics_userId_fetchedAt` " +
                "ON `flag_topics` (`userId`, `fetchedAt`)",
        )
    }
}

/**
 * v3 → v4 (Phase 2C, #145):
 *
 * Adds `subcat` to `topic_pages`. Phase 2C requires the sub-category id to build a
 * valid `message.php` GET form and a valid `bddpost.php` POST (cf.
 * `docs/specs/protocol-hfr.md` § POST `bddpost.php`). The parser extracts the value
 * from the topic page HTML (`input[name=subcat]`), the entity stores it next to
 * `cat`, `post`, `page`.
 *
 * Existing v3 rows are backfilled to `-1`, the `SUBCAT_UNKNOWN` sentinel meaning
 * "unknown, must be refreshed before any write flow"; write paths refuse it and it is
 * *never* transmitted to HFR. The `-1` default keeps Room's schema verification happy
 * without forcing a row rewrite (topic pages are short-lived cache; the next live fetch
 * replaces the sentinel).
 *
 * NOTE (#213, superseded write contract): this migration's original Phase 2C rationale
 * gated writes on `subcat > 0` and treated `subcat = 0` as a non-postable
 * moderator-space wire shape. #213 later **validated** (live capture of the IA cat=32
 * reply form, see `docs/specs/protocol-hfr.md` § POST `bddpost.php`) that `subcat = 0`
 * IS postable for a category without sub-category. Postability is now driven by
 * `Topic.canReply` (presence of the `bddpost` reply form), not by `subcat > 0`; only
 * the `-1` sentinel stays non-postable. The `-1` backfill here is unaffected.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE topic_pages ADD COLUMN subcat INTEGER NOT NULL DEFAULT -1",
        )
    }
}

/**
 * v5 → v6 (Phase 2 finish, #208):
 *
 * Adds `profileId` to `posts`. Stores the HFR numeric user id extracted from the
 * profile link `<a href="/hfr/profil-{N}.htm">` in each post's left toolbar. This
 * enables the profile bottom sheet tap without a network round-trip on a cache hit.
 *
 * Nullable on disk because:
 * - pre-v6 rows backfill to `NULL` (recovered on the next live fetch);
 * - « Publicité » rows and anonymous reads legitimately carry no profile link;
 * - HFR may stop rendering the link for certain post types in the future.
 *
 * Pure DDL, no row rewrite — posts are short-lived cache.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE posts ADD COLUMN profileId INTEGER")
    }
}

/**
 * v4 → v5 (Phase 2C, #146 round 2):
 *
 * Adds `quoteRef` to `posts`. Without this column, every fresh cache hit (the
 * common case once a topic has been refreshed once) would reset HFR's positional
 * `ref` to `null` because the mapper has no place to read it from. Since #227
 * that no longer controls « Citer » visibility (quote can use `numrep` alone),
 * but preserving the server-provided value remains the best-effort clear-link
 * contract. The column is nullable on disk for two reasons :
 *
 * - pre-v5 rows backfill to `NULL` (we never captured a `ref` for them; the
 *   next live fetch will set the real value),
 * - posts whose HFR HTML legitimately exposes no quote link (locked topic,
 *   anonymous read, future server-side change) keep `NULL` as the real value.
 *
 * Pure SQL, no row rewrite — posts are short-lived cache and the next
 * authenticated fetch overwrites every row with a parsed `quoteRef`.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE posts ADD COLUMN quoteRef INTEGER")
    }
}

/**
 * v6 → v7 (#213):
 *
 * Adds `canReply` to `topic_pages`. Postability is driven by the presence of the
 * `bddpost` reply form on the topic page (rendered only on an authenticated,
 * non-locked topic) — see `Topic.canReply`. Persisting it keeps the reply / quote /
 * edit buttons enabled on a cache hit without a network round-trip.
 *
 * Backfilled to `0` (`false`) for pre-v7 rows : they were written before we observed
 * the reply form, so they stay read-only until the next live authenticated fetch
 * surfaces a real value. We also mark those rows stale (`fetchedAt = 0`) so an
 * otherwise fresh authenticated cache row cannot keep the new write buttons hidden
 * for the full topic-page TTL after an app upgrade. Stored as `INTEGER NOT NULL`
 * (Room's Boolean encoding).
 *
 * One DDL step plus a deliberate cache invalidation row rewrite — topic pages are
 * short-lived cache, and the next live fetch is the source of truth for write
 * capability.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE topic_pages ADD COLUMN canReply INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE topic_pages SET fetchedAt = 0")
    }
}

/**
 * v7 → v8 (#362):
 *
 * Adds `editedAt` to `posts`. Stores the last-edit timestamp parsed from the post's
 * `div.edited` trailer (« Message édité par <auteur> le DD-MM-YYYY à HH:MM:SS »), so
 * the « Édité le … » line of the per-post menu survives a cache hit without a network
 * round-trip.
 *
 * Nullable on disk because:
 * - pre-v8 rows backfill to `NULL` (the next live fetch sets the real value);
 * - never-edited posts legitimately carry no edit trailer — including cited-but-never-
 *   edited posts whose `div.edited` only holds the « Message cité N fois » link.
 *
 * Pure DDL, no row rewrite — posts are short-lived cache (stored as epoch millis via
 * the `Instant` type converter, hence `INTEGER`).
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE posts ADD COLUMN editedAt INTEGER")
    }
}

/**
 * v8 → v9 — `flag_topics.isFavorite` (#384 follow-up, dev v118 feedback): the favori/étoile
 * decoration (`flag_owntopic == 3`) surfaced as its own column, independent of `type` (the
 * bucket), so a favorited topic listed under « Mes sujets » keeps its yellow dot.
 *
 * Pure DDL, no row rewrite. `NOT NULL DEFAULT 0` matches the entity's
 * `@ColumnInfo(defaultValue = "0")`; pre-v9 rows backfill to `false` and the next live fetch
 * sets the real value (flag lists are short-lived per-account cache).
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flag_topics ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v9 → v10 — `mp_read_positions` (#430, ADR-013 stage 1): per-account last-displayed page of
 * each private-message conversation, so reopening (or restoring after process death) lands past
 * the route's frozen opening page. Pure DDL, no backfill: positions only exist once the user
 * navigates with the new build, and the opening fallback (the inbox's last-page link) covers
 * absent rows.
 */
val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mp_read_positions` (
                `userId` TEXT NOT NULL,
                `threadId` INTEGER NOT NULL,
                `page` INTEGER NOT NULL,
                PRIMARY KEY(`userId`, `threadId`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * v10 → v11 — `editor_drafts` (#405): per-account cache of in-progress editor content so a
 * draft survives nav-entry destruction / process death. Pure DDL, no backfill. MP drafts
 * (`isPrivate = 1`) are purged on logout / account switch; every row is bounded by a 30-day
 * retention purge run on app start. The `draftKey` PK is `"<ownerId>|<contextKey>"` and the
 * dedicated `ownerId` column lets the logout purge target one account exactly (no LIKE scan).
 * The DDL matches the exported `11.json` `createSql` (column order, NOT NULL, nullable
 * `subject`/`recipients`, PK) so `runMigrationsAndValidate` is satisfied.
 */
val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `editor_drafts` (
                `draftKey` TEXT NOT NULL,
                `ownerId` TEXT NOT NULL,
                `body` TEXT NOT NULL,
                `subject` TEXT,
                `recipients` TEXT,
                `updatedAt` INTEGER NOT NULL,
                `isPrivate` INTEGER NOT NULL,
                PRIMARY KEY(`draftKey`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * v11 → v12 — `uploaded_images` (#459): per-account history of images uploaded through the editor
 * (provider, deletion handle, full + thumbnail URLs, dates), backing the « Mes images uploadées »
 * screen and deferred deletion. Pure DDL, no backfill — rows only exist once the user uploads with
 * the new build. Like `mp_read_positions` the rows are private per account and wiped on logout /
 * account switch (cf. `CacheInvalidator`).
 *
 * The DDL is hand-written to match the exported `12.json` `createSql` (column order = entity field
 * order, NOT NULL flags, nullable `thumbnailUrl` / `deleteHandle` / `expiresAt`, composite PK, and
 * the `userId, uploadedAt` index) so `runMigrationsAndValidate` is satisfied.
 *
 * NOTE orchestrateur: régénérer `12.json` via ksp (`./gradlew :core:database:kspDebugKotlin`) puis
 * vérifier que ce DDL correspond exactement au `createSql` exporté avant merge.
 */
val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `uploaded_images` (
                `userId` TEXT NOT NULL,
                `provider` TEXT NOT NULL,
                `picId` TEXT NOT NULL,
                `imageUrl` TEXT NOT NULL,
                `thumbnailUrl` TEXT,
                `deleteHandle` TEXT,
                `uploadedAt` INTEGER NOT NULL,
                `expiresAt` INTEGER,
                PRIMARY KEY(`userId`, `provider`, `picId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_uploaded_images_userId_uploadedAt` " +
                "ON `uploaded_images` (`userId`, `uploadedAt`)",
        )
    }
}

/**
 * v12 → v13 — `mp_storage_locations` (#6, ADR-014): per-account cache of the discovered MPStorage
 * conversation (the `post` id + the first post's `numreponse`), so the storage document is read
 * directly instead of re-scanning the whole MP inbox on every fetch. Pure DDL, no backfill — a row
 * only exists once the storage MP has been discovered with the new build. Like `mp_read_positions`
 * / `uploaded_images` the row is private per account and wiped on logout / account switch (cf.
 * `CacheInvalidator`).
 *
 * The DDL matches the exported `13.json` `createSql` (column order = entity field order, NOT NULL
 * flags, single-column PK) so `runMigrationsAndValidate` is satisfied.
 *
 * NOTE orchestrateur: régénérer `13.json` via ksp (`./gradlew :core:database:kspDebugKotlin`) puis
 * vérifier que ce DDL correspond exactement au `createSql` exporté avant merge.
 */
val MIGRATION_12_13: Migration = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mp_storage_locations` (
                `userId` TEXT NOT NULL,
                `threadId` INTEGER NOT NULL,
                `numreponse` INTEGER NOT NULL,
                PRIMARY KEY(`userId`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * v13 → v14 (#330):
 *
 * Adds `signature` to `posts`. Stores the author's signature block (the HFR
 * `<span class="signature">` trailer) as JSON in the same `PostContent` AST shape as `content`,
 * so the « Afficher les signatures » reading preference is a pure render-time switch — toggling
 * it never triggers a refetch.
 *
 * Nullable `TEXT` on disk because:
 * - pre-v14 rows backfill to `NULL` (the next live fetch sets the real value);
 * - most posts legitimately carry no signature.
 *
 * Pure DDL, no row rewrite — posts are short-lived cache and the next fetch overwrites every row
 * with a parsed (possibly null) signature.
 */
val MIGRATION_13_14: Migration = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE posts ADD COLUMN signature TEXT")
    }
}

/**
 * v14 → v15 (#863) — adds `posts.citedCount`, HFR's server-side « Message cité N fois »
 * counter (cross-page, authoritative — replaces the page-scoped client scan as the badge
 * source). Nullable `INTEGER` on disk because:
 * - pre-v15 rows backfill to `NULL` (the next live fetch sets the real value);
 * - never-cited posts legitimately carry no counter.
 *
 * Pure DDL, no row rewrite — posts are short-lived cache and the next fetch overwrites every
 * row with a parsed (possibly null) counter.
 */
val MIGRATION_14_15: Migration = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE posts ADD COLUMN citedCount INTEGER")
    }
}

/**
 * v15 → v16 (#638) — adds `flag_topics.lastPosition` (REST `last_position`, the 1-based global index
 * of the last post read) and `flag_topics.postsPerPage` (`results_per_page` of the topic's posts
 * href). Together they let `Flag.pageToOpen()` tell « stopped at the bottom of the page » from
 * « stopped mid-page » — the two cases that need opposite navigation.
 *
 * `lastPosition` is nullable so pre-v16 rows backfill to `NULL`, which `pageToOpen` treats as
 * « unknown » and degrades to the previous behaviour. `postsPerPage` takes `DEFAULT 40` (HFR's
 * value) so migrated rows stay usable until the next fetch overwrites them.
 *
 * Pure DDL, no row rewrite — flags are a short-lived cache (30 s TTL) and the next fetch replaces
 * every row.
 */
val MIGRATION_15_16: Migration = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flag_topics ADD COLUMN lastPosition INTEGER")
        db.execSQL("ALTER TABLE flag_topics ADD COLUMN postsPerPage INTEGER NOT NULL DEFAULT 40")
    }
}

/**
 * v16 → v17 (#1040/#1097) — adds the dormant, account-scoped private-message content cache.
 * The semantic [fr.forumhfr.redface2.core.model.PostContent] AST and signatures are stored through
 * the existing converters; raw HTML, request URLs, reply forms and authentication secrets are not.
 * Child rows cascade with their page, and the `(parent, ordinal)` index serves ordered rendering.
 */
val MIGRATION_16_17: Migration = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mp_thread_pages` (
                `userId` TEXT NOT NULL,
                `threadId` INTEGER NOT NULL,
                `page` INTEGER NOT NULL,
                `subject` TEXT NOT NULL,
                `correspondent` TEXT NOT NULL,
                `totalPages` INTEGER NOT NULL,
                `canReply` INTEGER NOT NULL,
                `isMultiRecipient` INTEGER NOT NULL,
                `fetchedAt` INTEGER NOT NULL,
                PRIMARY KEY(`userId`, `threadId`, `page`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mp_messages` (
                `userId` TEXT NOT NULL,
                `threadId` INTEGER NOT NULL,
                `page` INTEGER NOT NULL,
                `numreponse` INTEGER NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `author` TEXT NOT NULL,
                `date` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                `avatarUrl` TEXT,
                `isEditable` INTEGER NOT NULL,
                `isOwnPost` INTEGER NOT NULL,
                `quotedAuthors` TEXT NOT NULL,
                `postIndex` INTEGER,
                `quoteRef` INTEGER,
                `profileId` INTEGER,
                `editedAt` INTEGER,
                `citedCount` INTEGER,
                `signature` TEXT,
                PRIMARY KEY(`userId`, `threadId`, `page`, `numreponse`),
                FOREIGN KEY(`userId`, `threadId`, `page`)
                    REFERENCES `mp_thread_pages`(`userId`, `threadId`, `page`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_mp_messages_userId_threadId_page_ordinal`
            ON `mp_messages` (`userId`, `threadId`, `page`, `ordinal`)
            """.trimIndent(),
        )
    }
}

/**
 * v17 → v18 (#1112) — persists HFR's structural `messageModo` marker in both Post-backed caches.
 * The default keeps every pre-v18 topic post and private message neutral until its next live fetch;
 * no author-name heuristic is applied during migration.
 */
val MIGRATION_17_18: Migration = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE posts ADD COLUMN isModerationPost INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE mp_messages ADD COLUMN isModerationPost INTEGER NOT NULL DEFAULT 0",
        )
    }
}

/**
 * v18 → v19 (#340) — persists the nullable topic-post message tone. Existing rows keep `NULL`
 * naturally and are backfilled with the real HFR value on their next topic refresh.
 */
val MIGRATION_18_19: Migration = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE posts ADD COLUMN msgIcon INTEGER")
    }
}
