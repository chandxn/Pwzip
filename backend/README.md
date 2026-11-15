# AI Tutor backend

Requires Node.js 22.5 or newer. Copy `.env.example` to `.env`, set a random `JWT_SECRET` with at least 32 characters and a server-side `OPENAI_API_KEY`, then run `npm ci` and `npm start` from this directory. Set `ALLOWED_ORIGINS` to the web client origin(s); the Android WebView's local asset origin is `null`.

Deploy behind HTTPS. The SQLite database path is configurable with `DATABASE_PATH`. User accounts are password-hashed, signed bearer tokens scope lectures and conversation history to each account, and uploads are held in memory during transcription. Configure a persistent database volume and backups. OpenAI credentials are read only from the backend environment and are never included in Android assets or build configuration.

The API accepts user-authorized audio/video uploads and pasted transcripts only. It does not fetch course provider URLs or protected streams. `POST /api/lectures/:id/chat` streams grounded answers as server-sent events; `/study-guide` creates a transcript-based summary and timestamped chapters; `/api/realtime/session` issues a temporary Realtime credential for WebRTC voice tutoring.
