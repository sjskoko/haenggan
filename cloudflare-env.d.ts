declare namespace Cloudflare {
  interface Env {
    DB?: D1Database;
    BUCKET?: R2Bucket;
    OPENAI_API_KEY?: string;
    OPENAI_TEXT_MODEL?: string;
    OPENAI_TRANSCRIBE_MODEL?: string;
    VAPID_PUBLIC_KEY?: string;
    VAPID_PRIVATE_KEY?: string;
    PRIVATE_SCHEDULE_ENABLED?: string;
  }
}
