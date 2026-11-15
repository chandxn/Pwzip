(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.PwCaptionPipeline = api;
})(typeof globalThis === "object" ? globalThis : this, function () {
  "use strict";

  function normalizeCue(cue) {
    if (!cue) return null;
    const start = Number(cue.startTime ?? cue.start);
    const end = Number(cue.endTime ?? cue.end);
    const text = String(cue.text || "").replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim().slice(0, 1000);
    if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end < start || !text) return null;
    return { start, end, text };
  }

  function readVisibleCues(video) {
    const cues = [];
    if (!video || !video.textTracks) return cues;
    for (let i = 0; i < video.textTracks.length; i++) {
      const track = video.textTracks[i];
      if (!track || (track.kind !== "captions" && track.kind !== "subtitles") || track.mode !== "showing" || !track.activeCues) continue;
      for (let j = 0; j < track.activeCues.length; j++) {
        const cue = normalizeCue(track.activeCues[j]);
        if (cue) cues.push(cue);
      }
    }
    return cues;
  }

  function cueKey(cue) { return JSON.stringify([cue.start, cue.end, cue.text]); }

  function lessonKey(courseId, lessonId) {
    const course = String(courseId || "").trim();
    const lesson = String(lessonId || "").trim();
    return JSON.stringify([course, lesson]);
  }

  function mergeUniqueCues(existing, incoming) {
    const byKey = new Map();
    for (const cue of [...(existing || []), ...(incoming || [])]) {
      const clean = normalizeCue(cue);
      if (clean) byKey.set(cueKey(clean), clean);
    }
    return [...byKey.values()].sort((a, b) => a.start - b.start || a.end - b.end || a.text.localeCompare(b.text));
  }

  return { normalizeCue, readVisibleCues, cueKey, lessonKey, mergeUniqueCues };
});
