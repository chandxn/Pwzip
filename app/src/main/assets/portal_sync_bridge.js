(function() {
  try {
    if (window.__pwSyncBridgeV23_3) {
      if (window.__pwEmitPortalSync) {
        window.__pwEmitPortalSync("TAB_SYNC_REQUEST", "tab_re_emit", false);
      }
      return;
    }
    window.__pwSyncBridgeV23_3 = 1;

    var SESSION_ID = "sess_pwpm_v23_3_" + Math.random().toString(36).slice(2, 10);
    var lastInteractedVideo = null;

    var trackedContext = {
      seq: 0,
      lastHref: "",
      courseId: "",
      courseName: "",
      moduleId: "",
      moduleName: "",
      lessonId: "",
      lessonIdentityKey: "",
      lessonTitle: "",
      lastPosition: -1,
      lastDuration: 0,
      lastPlaybackState: "PAUSED",
      lastEventAtMs: 0,
      lastEventSource: "HTMLMediaElement.loadedmetadata",
      captionSegments: [],
      captionKeys: Object.create(null)
    };

    function resetTrackedLessonContext() {
      trackedContext.courseId = "";
      trackedContext.courseName = "";
      trackedContext.moduleId = "";
      trackedContext.moduleName = "";
      trackedContext.lessonId = "";
      trackedContext.lessonIdentityKey = "";
      trackedContext.lessonTitle = "";
      trackedContext.lastPosition = -1;
      trackedContext.lastDuration = 0;
      trackedContext.lastPlaybackState = "PAUSED";
      trackedContext.captionSegments = [];
      trackedContext.captionKeys = Object.create(null);
    }

    function lessonIdentityKey(courseId, lessonId) {
      return window.PwCaptionPipeline
        ? window.PwCaptionPipeline.lessonKey(courseId, lessonId)
        : String(courseId || "") + "|" + String(lessonId || "");
    }

    var PROGRESS_TEXT_REGEX = /^(\d+%(\s*(complete|completed|watched|done))?|\d+\s*(\/|of)\s*\d+(\s*(lessons?|lectures?|modules?|videos?|chapters?|completed|done))?|\d{1,2}:\d{2}(:\d{2})?(\s*\/\s*\d{1,2}:\d{2}(:\d{2})?)?)$/i;

    function isBlockedSloganOrProgress(s) {
      if (!s || typeof s !== "string") return true;
      var norm = s.replace(/\s+/g, " ").trim().toLowerCase();
      if (norm.length < 3 || norm.length > 220) return true;
      if (PROGRESS_TEXT_REGEX.test(norm)) return true;
      if (norm.indexOf("learn skills that matter") !== -1 ||
          norm.indexOf("watch full video to complete") !== -1 ||
          norm.indexOf("rate your mentor") !== -1 ||
          norm.indexOf("unsupported keysystem") !== -1 ||
          norm.indexOf("streak") !== -1 ||
          norm.indexOf("dashboard") !== -1 ||
          norm.indexOf("leaderboard") !== -1 ||
          norm.indexOf("in progress") !== -1 ||
          norm.indexOf("% complete") !== -1 ||
          norm.indexOf("% watched") !== -1) {
        return true;
      }
      var blocked = {
        "pw skills": 1, "physics wallah": 1, "dashboard": 1, "overview": 1,
        "my courses": 1, "all courses": 1, "enrolled courses": 1,
        "continue learning": 1, "start learning": 1, "courses": 1, "home": 1,
        "login": 1, "sign in": 1, "sign up": 1, "mandatory": 1, "attachment": 1,
        "attachments": 1, "notes": 1, "rating": 1, "support": 1, "doubts": 1,
        "q&a": 1, "resources": 1, "current module": 1, "unspecified course": 1,
        "active lesson": 1, "live / unknown": 1, "resolution": 1,
        "playback speed": 1, "auto": 1, "1x": 1, "completed": 1, "watched": 1
      };
      return Boolean(blocked[norm]);
    }

    /**
     * Validates a genuine provider identifier (lessonId, courseId, moduleId).
     * Rejects empty strings, whitespace/progress phrases, percentages, ratios, and route keywords.
     */
    function isValidProviderId(rawId) {
      if (!rawId || typeof rawId !== "string") return false;
      var trimmed = rawId.trim();
      if (trimmed.length < 3 || trimmed.length > 128) return false;
      if (/\s/.test(trimmed) || trimmed.indexOf("%") !== -1 || trimmed.indexOf("/") !== -1 || trimmed.indexOf(":") !== -1) {
        return false;
      }
      if (PROGRESS_TEXT_REGEX.test(trimmed)) return false;
      var lower = trimmed.toLowerCase();
      if (/^(dashboard|overview|home|login|profile|courses|batches|study|learn|watch|lecture|lesson|video|null|undefined|none|unknown|unresolved|in_progress|completed|watched)$/.test(lower)) {
        return false;
      }
      if (lower.indexOf("streak") !== -1 || lower.indexOf("progress") !== -1 || lower.indexOf("complete") !== -1 || lower.indexOf("lesson_") === 0) {
        return false;
      }
      return /^[A-Za-z0-9_-]+$/.test(trimmed);
    }

    function cleanProviderText(s) {
      if (!s || typeof s !== "string") return "";
      var c = s.replace(/\|?\s*PW\s*Skills.*$/i, "")
        .replace(/-\s*PW\s*Skills.*$/i, "")
        .replace(/^[☰‹<>|\s]+/, "")
        .replace(/\s+/g, " ")
        .trim();
      return isBlockedSloganOrProgress(c) ? "" : c;
    }

    function parseTimeText(t) {
      if (!t || typeof t !== "string") return -1;
      var parts = t.trim().split(":").map(function(x) { return parseInt(x, 10); });
      if (parts.some(isNaN)) return -1;
      if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
      if (parts.length === 2) return parts[0] * 60 + parts[1];
      return -1;
    }

    function formatTimeHms(sec) {
      sec = Math.max(0, Math.floor(Number(sec) || 0));
      var h = Math.floor(sec / 3600);
      var m = Math.floor((sec % 3600) / 60);
      var s = sec % 60;
      if (h > 0) return h + ":" + String(m).padStart(2, "0") + ":" + String(s).padStart(2, "0");
      return String(m).padStart(2, "0") + ":" + String(s).padStart(2, "0");
    }

    function selectActiveLectureVideo() {
      var list = document.querySelectorAll ? document.querySelectorAll("video") : [];
      if (!list || !list.length) return { video: null, index: -1, total: 0 };
      var bestVideo = null;
      var bestScore = -9999;
      var bestIdx = -1;
      for (var i = 0; i < list.length; i++) {
        var v = list[i];
        if (!v || v.isConnected === false) continue;
        var score = 0;
        if (v === lastInteractedVideo) score += 400;
        if (!v.paused && !v.ended && v.readyState >= 2) score += 600;
        if (!v.muted && v.volume > 0) score += 250;
        if (v.loop) score -= 500;
        var dur = (isFinite(v.duration) && v.duration > 0) ? Number(v.duration) : 0;
        if (dur > 30) score += 200;
        if (Number(v.currentTime) > 0) score += 150;
        if (v.getBoundingClientRect) {
          var r = v.getBoundingClientRect();
          if (r.width >= 180 && r.height >= 100) score += 150;
        }
        if (score > bestScore) {
          bestScore = score;
          bestVideo = v;
          bestIdx = i;
        }
      }
      return { video: bestVideo, index: bestIdx, total: list.length };
    }

    // Read only a visible player clock rendered in the same DOM subtree as the media element.
    // Some portal skins render the authoritative clock outside the HTMLMediaElement.
    function readVisiblePlayerClock(video) {
      if (!video) return null;
      var root = video;
      for (var depth = 0; depth < 6 && root.parentElement; depth++) {
        root = root.parentElement;
        var candidates = root.querySelectorAll ? root.querySelectorAll("span,div,button,[aria-label],[class*='time'],[class*='progress']") : [];
        var best = null;
        for (var i = 0; i < candidates.length; i++) {
          var el = candidates[i];
          if (!el || !el.getBoundingClientRect) continue;
          var rect = el.getBoundingClientRect();
          if (rect.width <= 0 || rect.height <= 0 || rect.bottom < 0 || rect.top > (window.innerHeight || 900)) continue;
          var text = String(el.getAttribute("aria-label") || el.textContent || "").replace(/\\s+/g, " ").trim();
          var match = text.match(/^(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*\\/\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?)$/);
          if (!match) continue;
          var current = parseTimeText(match[1]);
          var duration = parseTimeText(match[2]);
          if (current < 0 || duration <= 0 || current > duration + 2) continue;
          best = { currentPosition: current, duration: duration, source: "visible-player-clock" };
          break;
        }
        if (best) return best;
      }
      return null;
    }

    /**
     * Stateless metadata extraction from current portal page ONLY.
     * Never falls back to `trackedContext` for `lessonId` or `lessonTitle`!
     */
    function extractCurrentPageMetadata() {
      var meta = {
        courseId: "",
        courseName: "",
        programme: "",
        moduleId: "",
        moduleName: "",
        lessonId: "",
        lessonTitle: "",
        identitySource: "",
        explicitSharedExportEntitled: false,
        explicitAppOfflineEntitled: false,
        explicitPersistentDrmEntitled: false,
        entitlementSource: null,
        attachments: [],
        notes: "",
        transcript: ""
      };

      // 1. Check URL path segments (`/course/<id>/lesson/<id>`)
      try {
        var parts = (location.pathname || "").split("/").filter(Boolean);
        for (var p = 0; p < parts.length; p++) {
          var seg = parts[p].toLowerCase();
          if ((seg === "course" || seg === "courses" || seg === "batch" || seg === "study" || seg === "learn") && parts[p + 1]) {
            if (isValidProviderId(parts[p + 1])) meta.courseId = parts[p + 1].trim();
          }
          if ((seg === "lesson" || seg === "lecture" || seg === "watch" || seg === "video") && parts[p + 1]) {
            if (isValidProviderId(parts[p + 1])) {
              meta.lessonId = parts[p + 1].trim();
              meta.identitySource = "URL.pathname";
            }
          }
        }
      } catch (e) {}

      // 2. Check selected/active lesson item in DOM first (takes priority over stale __NEXT_DATA__ on SPA clicks)
      var selectedDomLessonId = "";
      var selectedDomLessonTitle = "";
      try {
        if (document.querySelector) {
          var activeEl = document.querySelector(
            "[data-lesson-id][class*='active'], [data-lecture-id][class*='active'], [aria-current='true'][data-lesson-id], [aria-current='true'][data-lecture-id], [data-selected-lesson='true']"
          );
          if (activeEl) {
            var rawActiveId = String(activeEl.getAttribute("data-lesson-id") || activeEl.getAttribute("data-lecture-id") || "").trim();
            if (isValidProviderId(rawActiveId)) {
              selectedDomLessonId = rawActiveId;
            }
            var rawActiveTitle = cleanProviderText(activeEl.getAttribute("data-lesson-title") || activeEl.getAttribute("title") || activeEl.textContent || "");
            if (rawActiveTitle) {
              selectedDomLessonTitle = rawActiveTitle;
            }
          }
        }
      } catch (e) {}

      // 3. Check `__NEXT_DATA__` provider payload
      try {
        var nextScript = document.getElementById ? document.getElementById("__NEXT_DATA__") : null;
        if (nextScript && nextScript.textContent) {
          var nextData = JSON.parse(nextScript.textContent);
          var pp = (nextData && nextData.props && nextData.props.pageProps) || {};
          var cObj = pp.course || pp.batch || pp.courseDetails || {};
          var mObj = pp.module || pp.section || pp.chapter || {};
          var lObj = pp.lesson || pp.lecture || pp.videoDetails || {};

          var ndCourseId = String(pp.courseId || pp.batchId || cObj._id || cObj.id || cObj.slug || "").trim();
          if (!meta.courseId && isValidProviderId(ndCourseId)) meta.courseId = ndCourseId;
          meta.courseName = cleanProviderText(pp.courseName || pp.courseTitle || cObj.title || cObj.name) || meta.courseName;
          meta.programme = cleanProviderText(pp.programme || pp.programName || cObj.programName) || meta.programme;

          var ndModId = String(pp.moduleId || mObj._id || mObj.id || "").trim();
          if (isValidProviderId(ndModId)) meta.moduleId = ndModId;
          meta.moduleName = cleanProviderText(pp.moduleName || pp.moduleTitle || mObj.title || mObj.name) || meta.moduleName;

          var ndLessonId = String(pp.lessonId || pp.lectureId || lObj._id || lObj.id || "").trim();
          var ndLessonTitle = cleanProviderText(pp.lessonTitle || pp.lectureTitle || lObj.title || lObj.name);

          // If the user clicked a different lesson in the SPA DOM whose title differs from initial __NEXT_DATA__,
          // do NOT reuse the stale __NEXT_DATA__ lessonId!
          if (selectedDomLessonTitle && ndLessonTitle && selectedDomLessonTitle !== ndLessonTitle) {
            meta.lessonTitle = selectedDomLessonTitle;
            meta.lessonId = selectedDomLessonId; // "" if unresolved on the newly selected DOM item!
            meta.identitySource = selectedDomLessonId ? "DOM.selectedLesson" : "DOM.selectedTitleOnly_IdUnresolved";
          } else {
            if (!meta.lessonId && isValidProviderId(ndLessonId)) {
              meta.lessonId = ndLessonId;
              meta.identitySource = "__NEXT_DATA__";
            }
            if (ndLessonTitle) {
              meta.lessonTitle = ndLessonTitle;
            }
          }

          // Check explicit provider offline entitlement in __NEXT_DATA__
          var ent = lObj.offlineEntitlement || pp.offlineEntitlement || null;
          if (ent && typeof ent === "object") {
            if (ent.allowSharedExport === true) {
              meta.explicitSharedExportEntitled = true;
              meta.entitlementSource = "__NEXT_DATA__.lesson.offlineEntitlement";
            }
            if (ent.allowAppOffline === true) {
              meta.explicitAppOfflineEntitled = true;
              meta.entitlementSource = "__NEXT_DATA__.lesson.offlineEntitlement";
            }
            if (ent.persistentLicenseAllowed === true && typeof ent.offlineLicenseUrl === "string" && ent.offlineLicenseUrl.indexOf("https://") === 0) {
              meta.explicitPersistentDrmEntitled = true;
              meta.entitlementSource = "__NEXT_DATA__.lesson.offlineEntitlement";
            }
          }
        }
      } catch (e) {}

      if (selectedDomLessonId && !meta.lessonId) {
        meta.lessonId = selectedDomLessonId;
        meta.identitySource = "DOM.selectedLesson";
      }
      if (selectedDomLessonTitle && !meta.lessonTitle) {
        meta.lessonTitle = selectedDomLessonTitle;
      }

      // 4. Resolve a visible lesson heading from common portal DOM structures.
      // Headings often wrap text in spans; do not reject nodes with children.
      // This supplies a title only. It never fabricates a provider lesson ID.
      try {
        if (!meta.lessonTitle && document.querySelectorAll) {
          var headings = document.querySelectorAll(
            "[data-testid*='lecture-title'], [data-testid*='lesson-title'], " +
            "[class*='lecture-title'], [class*='lesson-title'], " +
            "[aria-label*='lecture'], [aria-label*='lesson'], h1, h2, h3"
          );
          var bestTitle = "";
          var bestId = "";
          var bestScore = -1;
          for (var i = 0; i < headings.length; i++) {
            var el = headings[i];
            if (!el || (el.closest && el.closest("nav, [role='navigation'], [class*='navbar'], [class*='streak'], [class*='progress'], [class*='dashboard']"))) continue;
            var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
            if (rect && (rect.width < 40 || rect.height < 10 || rect.bottom < 0 || rect.top > (window.innerHeight || 900))) continue;
            var candidateTitle = cleanProviderText(
              el.getAttribute("data-lesson-title") ||
              el.getAttribute("title") ||
              el.getAttribute("aria-label") ||
              el.textContent || ""
            );
            if (!candidateTitle || candidateTitle.length < 4) continue;

            var score = 0;
            var cls = String(el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className || "").toLowerCase();
            var testId = String(el.getAttribute("data-testid") || "").toLowerCase();
            if (cls.indexOf("lecture-title") !== -1 || cls.indexOf("lesson-title") !== -1) score += 100;
            if (testId.indexOf("lecture-title") !== -1 || testId.indexOf("lesson-title") !== -1) score += 120;
            if (el.getAttribute("data-lesson-title")) score += 130;
            if (rect) {
              if (rect.top >= 0 && rect.top < (window.innerHeight || 900) * 0.65) score += 10;
              if (rect.width > 80) score += 5;
            }
            if (score > bestScore) {
              bestScore = score;
              bestTitle = candidateTitle;
              var ownId = String(el.getAttribute("data-lesson-id") || el.getAttribute("data-lecture-id") || "").trim();
              bestId = isValidProviderId(ownId) ? ownId : "";
            }
          }
          if (bestTitle) {
            meta.lessonTitle = bestTitle;
            if (bestId) {
              meta.lessonId = bestId;
              meta.identitySource = "DOM.headingAttribute";
            } else if (meta.identitySource === "__NEXT_DATA__") {
              meta.lessonId = "";
              meta.identitySource = "DOM.headingOnly_IdUnresolved";
            }
          }
        }
      } catch (e) {}

      // 5. Fallback for portal layouts that render the lesson heading as a plain div
      // (the current PW mobile layout does this instead of h1/h2/h3).
      // This is title-only metadata: it never creates or guesses a provider lesson ID.
      try {
        if (!meta.lessonTitle && document.querySelectorAll) {
          var nodes = document.querySelectorAll("body *");
          var fallbackTitle = "";
          var fallbackScore = -1;
          var viewportHeight = window.innerHeight || 900;
          var scanLimit = Math.min(nodes.length, 900);
          for (var fi = 0; fi < scanLimit; fi++) {
            var candidate = nodes[fi];
            if (!candidate || !candidate.getBoundingClientRect || !candidate.textContent) continue;
            if (candidate.children && candidate.children.length > 3) continue;
            if (candidate.closest && candidate.closest("nav, [role='navigation'], [class*='navbar'], [class*='sidebar'], [class*='streak'], [class*='progress'], [class*='dashboard']")) continue;
            var fr = candidate.getBoundingClientRect();
            if (fr.width < 55 || fr.height < 10 || fr.top < 4 || fr.top > viewportHeight * 0.42) continue;
            var candidateText = cleanProviderText(
              candidate.getAttribute("data-lesson-title") ||
              candidate.getAttribute("aria-label") ||
              candidate.getAttribute("title") ||
              candidate.textContent || ""
            );
            if (!candidateText || candidateText.length < 4 || candidateText.length > 100) continue;
            var fcls = String(candidate.className && candidate.className.baseVal !== undefined ? candidate.className.baseVal : candidate.className || "").toLowerCase();
            var fscore = 10 + Math.max(0, 80 - Math.abs(fr.top - viewportHeight * 0.16));
            if (candidate.getAttribute("role") === "heading") fscore += 80;
            if (/title|heading|truncate|font-semibold|font-bold/.test(fcls)) fscore += 35;
            if (candidate.tagName === "H1" || candidate.tagName === "H2" || candidate.tagName === "H3") fscore += 60;
            if (fscore > fallbackScore) {
              fallbackScore = fscore;
              fallbackTitle = candidateText;
            }
          }
          if (fallbackTitle) meta.lessonTitle = fallbackTitle;
        }
      } catch (e) {}

      return meta;
    }

    /**
     * Inspects media format and provider entitlement using standard read-only HTMLMediaElement properties.
     * Preserves native browser EME/DRM negotiation using standard HTMLMediaElement properties.
     */
    function classifyActiveMedia(v, meta) {
      if (!v) {
        return {
          mediaFormat: "WIDEVINE_PROTECTED_STREAMING",
          downloadEligibility: "PROVIDER_OFFLINE_ACCESS_UNAVAILABLE",
          approvedDownloadUrl: "",
          offlineLicenseUrl: "",
          persistentLicenseAllowed: false
        };
      }
      var src = (v.currentSrc || v.src || "").trim();
      var sourceEl = v.querySelector ? v.querySelector("source[src], source[type]") : null;
      if (!src && sourceEl) src = (sourceEl.src || "").trim();
      var mimeType = (sourceEl && sourceEl.type ? sourceEl.type : "").toLowerCase();

      var offlineLicUrl = v.getAttribute ? (v.getAttribute("data-offline-license-url") || "").trim() : "";
      var persistentAllowed = Boolean(
        meta.explicitPersistentDrmEntitled &&
        v.getAttribute && v.getAttribute("data-persistent-license-allowed") === "true" &&
        offlineLicUrl.indexOf("https://") === 0
      );

      var isDrm = Boolean(
        v.mediaKeys ||
        v.__pwEncrypted ||
        (v.getAttribute && v.getAttribute("data-drm") === "widevine") ||
        mimeType.indexOf("drm") !== -1 ||
        src.indexOf("blob:") === 0
      );

      if (isDrm) {
        return {
          mediaFormat: "WIDEVINE_PROTECTED_STREAMING",
          downloadEligibility: persistentAllowed ? "AUTHORIZED_PERSISTENT_DRM" : "PROVIDER_OFFLINE_ACCESS_UNAVAILABLE",
          approvedDownloadUrl: (persistentAllowed && src.indexOf("https://") === 0) ? src : "",
          offlineLicenseUrl: persistentAllowed ? offlineLicUrl : "",
          persistentLicenseAllowed: persistentAllowed
        };
      }

      var hasDomDownloadApproval = Boolean(
        v.getAttribute && (
          v.getAttribute("data-download-approved") === "true" ||
          v.hasAttribute("data-provider-downloadable")
        )
      );
      var cleanPath = src.split("?")[0].toLowerCase();
      var isHttps = src.indexOf("https://") === 0;

      if (cleanPath.endsWith(".mp4") || mimeType === "video/mp4") {
        var mp4Entitled = Boolean(isHttps && (meta.explicitSharedExportEntitled || hasDomDownloadApproval));
        if (hasDomDownloadApproval && !meta.entitlementSource) {
          meta.explicitSharedExportEntitled = true;
          meta.entitlementSource = "DOM[data-download-approved=true]";
        }
        return {
          mediaFormat: "PROGRESSIVE_MP4",
          downloadEligibility: mp4Entitled ? "AUTHORIZED_SHARED_EXPORT" : "PROVIDER_OFFLINE_ACCESS_UNAVAILABLE",
          approvedDownloadUrl: mp4Entitled ? src : "",
          offlineLicenseUrl: "",
          persistentLicenseAllowed: false
        };
      }

      if (cleanPath.endsWith(".m3u8") || mimeType.indexOf("mpegurl") !== -1 || cleanPath.endsWith(".mpd") || mimeType.indexOf("dash+xml") !== -1) {
        var fmt = (cleanPath.endsWith(".mpd") || mimeType.indexOf("dash+xml") !== -1) ? "NON_DRM_DASH" : "NON_DRM_HLS";
        var adaptiveEntitled = Boolean(isHttps && (meta.explicitAppOfflineEntitled || hasDomDownloadApproval));
        if (hasDomDownloadApproval && !meta.entitlementSource) {
          meta.explicitAppOfflineEntitled = true;
          meta.entitlementSource = "DOM[data-download-approved=true]";
        }
        return {
          mediaFormat: fmt,
          downloadEligibility: adaptiveEntitled ? "AUTHORIZED_APP_OFFLINE" : "PROVIDER_OFFLINE_ACCESS_UNAVAILABLE",
          approvedDownloadUrl: adaptiveEntitled ? src : "",
          offlineLicenseUrl: "",
          persistentLicenseAllowed: false
        };
      }

      return {
        mediaFormat: "WIDEVINE_PROTECTED_STREAMING",
        downloadEligibility: "PROVIDER_OFFLINE_ACCESS_UNAVAILABLE",
        approvedDownloadUrl: "",
        offlineLicenseUrl: "",
        persistentLicenseAllowed: false
      };
    }

    function buildSessionPayload(eventName, positionSource) {
      var nowMs = Date.now();
      var currentHref = location.href || "";
      var meta = extractCurrentPageMetadata();

      // Detect route or lesson change and clear stale trackedContext immediately!
      var lessonChanged = Boolean(
        (trackedContext.lastHref && currentHref !== trackedContext.lastHref) ||
        (trackedContext.lessonTitle && meta.lessonTitle !== trackedContext.lessonTitle) ||
        (trackedContext.lessonId && meta.lessonId !== trackedContext.lessonId) ||
        (trackedContext.lessonIdentityKey && meta.lessonId && lessonIdentityKey(meta.courseId, meta.lessonId) !== trackedContext.lessonIdentityKey)
      );
      if (lessonChanged) {
        resetTrackedLessonContext();
      }
      trackedContext.lastHref = currentHref;
      trackedContext.seq = (trackedContext.seq || 0) + 1;
      trackedContext.lastEventAtMs = nowMs;
      if (positionSource && positionSource !== "tab_re_emit") {
        trackedContext.lastEventSource = positionSource;
      }

      // Requirement B: Both genuine provider `lessonId` AND `lessonTitle` must be confirmed!
      // Never reuse a previous lessonId when a new lesson is selected with an unresolved ID.
      var identityConfirmed = Boolean(isValidProviderId(meta.lessonId) && meta.lessonTitle && meta.lessonTitle.length >= 3);
      if (!identityConfirmed) {
        resetTrackedLessonContext();
        return {
          hasActiveLesson: false,
          status: "ACTIVE_LESSON_UNRESOLVED",
          connectionState: "ACTIVE_LESSON_UNRESOLVED",
          sessionId: SESSION_ID,
          seq: trackedContext.seq,
          event: "ACTIVE_LESSON_UNRESOLVED",
          reason: !meta.lessonTitle ? "LESSON_TITLE_UNRESOLVED" : "LESSON_ID_UNRESOLVED",
          observedLessonTitle: meta.lessonTitle || null,
          observedCourseId: meta.courseId || null,
          source: trackedContext.lastEventSource,
          lastUpdatedAt: nowMs,
          updatedAtMs: nowMs
        };
      }

      // Update trackedContext with confirmed current lesson identity
      if (trackedContext.lessonId && trackedContext.lessonId !== meta.lessonId) {
        trackedContext.captionSegments = [];
        trackedContext.captionKeys = Object.create(null);
      }
      trackedContext.lessonId = meta.lessonId;
      trackedContext.lessonIdentityKey = lessonIdentityKey(meta.courseId, meta.lessonId);
      trackedContext.lessonTitle = meta.lessonTitle;
      trackedContext.courseId = meta.courseId;
      trackedContext.courseName = meta.courseName;
      trackedContext.moduleId = meta.moduleId;
      trackedContext.moduleName = meta.moduleName;

      var sel = selectActiveLectureVideo();
      var v = sel.video;
      var pos = 0;
      var dur = 0;
      var clockSource = "";
      if (v) {
        pos = Math.max(0, Number(v.currentTime) || 0);
        if (isFinite(v.duration) && Number(v.duration) > 0) dur = Number(v.duration);
        if (pos <= 0 || dur <= 0) {
          var visibleClock = readVisiblePlayerClock(v);
          if (visibleClock) {
            if (pos <= 0) pos = visibleClock.currentPosition;
            if (dur <= 0) dur = visibleClock.duration;
            clockSource = visibleClock.source;
          }
        }
      }
      trackedContext.lastPosition = pos;
      trackedContext.lastDuration = dur;

      var pState = "PAUSED";
      if (v) {
        if (v.ended || eventName === "ENDED") pState = "ENDED";
        else if (!v.paused || eventName === "PLAY") pState = "PLAYING";
      }
      trackedContext.lastPlaybackState = pState;

      // Read only cues from a caption/subtitle track already set to "showing" by the player/user.
      // Never enable tracks or fetch caption/media URLs; this captures only played, visible cues.
      if (v && window.PwCaptionPipeline) {
        var visibleCues = window.PwCaptionPipeline.readVisibleCues(v);
        for (var ci = 0; ci < visibleCues.length; ci++) {
          var cue = visibleCues[ci];
          var key = window.PwCaptionPipeline.cueKey(cue);
          if (trackedContext.captionKeys[key]) continue;
          trackedContext.captionKeys[key] = true;
          trackedContext.captionSegments.push(cue);
        }
      }
      var mediaInfo = classifyActiveMedia(v, meta);

      return {
        hasActiveLesson: true,
        status: "ACTIVE_LESSON_CONFIRMED",
        connectionState: "CONNECTED",
        sessionId: SESSION_ID,
        seq: trackedContext.seq,
        event: eventName,
        source: trackedContext.lastEventSource,
        positionSource: trackedContext.lastEventSource,
        courseId: meta.courseId,
        courseName: meta.courseName,
        courseTitle: meta.courseName,
        programme: meta.programme,
        moduleId: meta.moduleId,
        moduleName: meta.moduleName,
        moduleTitle: meta.moduleName,
        lessonId: meta.lessonId,
        lessonTitle: meta.lessonTitle,
        identitySource: meta.identitySource,
        currentPosition: pos,
        duration: dur,
        playbackClockSource: clockSource || (v ? "HTMLMediaElement" : "unavailable"),
        formattedPosition: formatTimeHms(pos),
        formattedDuration: dur > 0 ? formatTimeHms(dur) : "Not exposed by provider",
        playbackState: pState,
        mediaFormat: mediaInfo.mediaFormat,
        downloadEligibility: mediaInfo.downloadEligibility,
        explicitSharedExportEntitled: meta.explicitSharedExportEntitled,
        explicitAppOfflineEntitled: meta.explicitAppOfflineEntitled,
        explicitPersistentDrmEntitled: meta.explicitPersistentDrmEntitled,
        entitlementSource: meta.entitlementSource,
        approvedDownloadUrl: mediaInfo.approvedDownloadUrl,
        offlineLicenseUrl: mediaInfo.offlineLicenseUrl,
        persistentLicenseAllowed: mediaInfo.persistentLicenseAllowed,
        attachments: meta.attachments,
        notes: meta.notes,
        transcript: meta.transcript,
        captionSource: trackedContext.captionSegments.length ? "visible-html5-text-track" : "unavailable",
        captionSegments: trackedContext.captionSegments.slice(-100),
        portalUrl: currentHref,
        lastUpdatedAt: nowMs,
        updatedAtMs: nowMs
      };
    }

    function emitPortalSync(eventName, positionSource) {
      try {
        var payload = buildSessionPayload(eventName, positionSource);
        if (!payload) return;
        console.log("__PW_SYNC__:" + JSON.stringify(payload));
      } catch (e) {}
    }

    window.__pwEmitPortalSync = emitPortalSync;

    window.__pwPortalControl = function(cmd) {
      try {
        var sel = selectActiveLectureVideo();
        var v = sel.video;
        if (v) {
          if (cmd === "toggle") {
            if (v.paused) v.play(); else v.pause();
          } else if (cmd === "seek_back") {
            v.currentTime = Math.max(0, (v.currentTime || 0) - 10);
          } else if (cmd === "seek_fwd") {
            v.currentTime = (v.currentTime || 0) + 10;
          } else if (cmd.indexOf("speed:") === 0) {
            v.playbackRate = parseFloat(cmd.split(":")[1]) || 1.0;
          }
        }
        emitPortalSync("PLAYBACK_COMMAND", "HTMLMediaElement.remote_" + cmd);
      } catch (e) {}
    };

    var mediaEvents = ["loadedmetadata", "play", "pause", "playing", "timeupdate", "seeking", "seeked", "durationchange", "ended", "encrypted"];
    mediaEvents.forEach(function(evName) {
      window.addEventListener(evName, function(e) {
        try {
          var t = e && e.target;
          if (!t || t.tagName !== "VIDEO") return;
          if (evName === "encrypted") {
            t.__pwEncrypted = true;
          }
          // Remember the actual media element that emitted the event, including pause/seek
          // events; otherwise a different hidden/thumbnail video can win the selector.
          if (!t.loop && t.isConnected !== false) {
            var eventRect = t.getBoundingClientRect ? t.getBoundingClientRect() : null;
            if (!eventRect || (eventRect.width >= 120 && eventRect.height >= 70)) lastInteractedVideo = t;
          }
          var mappedEvent = (evName === "loadedmetadata" || evName === "durationchange")
            ? "LESSON_CHANGED"
            : (evName === "timeupdate" ? "PLAYBACK_PROGRESS" : (evName === "playing" ? "PLAY" : evName.toUpperCase()));
          emitPortalSync(mappedEvent, "HTMLMediaElement." + evName);
        } catch (err) {}
      }, true);
    });

    emitPortalSync("BRIDGE_INIT", "HTMLMediaElement.loadedmetadata");
  } catch (e) {}
})();
