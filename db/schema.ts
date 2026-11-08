import { sqliteTable, text, integer, index, uniqueIndex } from "drizzle-orm/sqlite-core";
export const entries = sqliteTable("entries", {
  id: text("id").primaryKey(), userId: text("user_id").notNull(),
  date: text("date").notNull(), title: text("title").notNull(), transcript: text("transcript").notNull(),
  summary: text("summary").notNull().default(""), summaryOrigin: text("summary_origin").notNull().default("none"),
  mode: text("mode").notNull().default("listen"), mood: text("mood"), audioKey: text("audio_key"),
  duration: integer("duration").notNull().default(0), revision: integer("revision").notNull().default(1),
  createdAt: text("created_at").notNull(), updatedAt: text("updated_at").notNull(),
}, t => [index("entries_user_date").on(t.userId,t.date)]);
export const weeks = sqliteTable("weeks", {
  id: text("id").primaryKey(), userId: text("user_id").notNull(), weekStart: text("week_start").notNull(),
  sourceSignature: text("source_signature").notNull(), report: text("report").notNull(),
  intention: text("intention").notNull().default(""), updatedAt: text("updated_at").notNull(),
}, t => [uniqueIndex("weeks_user_start").on(t.userId,t.weekStart)]);
export const aiUsage = sqliteTable("ai_usage", {
  id: text("id").primaryKey(), userId: text("user_id").notNull(), day: text("day").notNull(),
  count: integer("count").notNull().default(0),
});
export const subscriptions = sqliteTable("subscriptions", {
  id:text("id").primaryKey(), userId:text("user_id").notNull(), subscription:text("subscription").notNull(),
  lastSentDate:text("last_sent_date"), enabled:integer("enabled").notNull().default(1), createdAt:text("created_at").notNull(),
}, t=>[index("subscriptions_user").on(t.userId)]);
