CREATE TABLE `ai_usage` (
	`id` text PRIMARY KEY NOT NULL,
	`user_id` text NOT NULL,
	`day` text NOT NULL,
	`count` integer DEFAULT 0 NOT NULL
);
--> statement-breakpoint
CREATE TABLE `entries` (
	`id` text PRIMARY KEY NOT NULL,
	`user_id` text NOT NULL,
	`date` text NOT NULL,
	`title` text NOT NULL,
	`transcript` text NOT NULL,
	`summary` text DEFAULT '' NOT NULL,
	`summary_origin` text DEFAULT 'none' NOT NULL,
	`mode` text DEFAULT 'listen' NOT NULL,
	`mood` text,
	`audio_key` text,
	`duration` integer DEFAULT 0 NOT NULL,
	`revision` integer DEFAULT 1 NOT NULL,
	`created_at` text NOT NULL,
	`updated_at` text NOT NULL
);
--> statement-breakpoint
CREATE INDEX `entries_user_date` ON `entries` (`user_id`,`date`);--> statement-breakpoint
CREATE TABLE `weeks` (
	`id` text PRIMARY KEY NOT NULL,
	`user_id` text NOT NULL,
	`week_start` text NOT NULL,
	`source_signature` text NOT NULL,
	`report` text NOT NULL,
	`intention` text DEFAULT '' NOT NULL,
	`updated_at` text NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `weeks_user_start` ON `weeks` (`user_id`,`week_start`);