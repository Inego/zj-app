// Public GIS web OAuth client ID. Empty disables Google Sign-In in this build.
// Override at runtime with ?googleClientId=... or by editing this file.
window.__ZJ_GOOGLE_CLIENT_ID__ = window.__ZJ_GOOGLE_CLIENT_ID__ || "__GOOGLE_WEB_CLIENT_ID__";

// API backend base URL. Empty / unset = same origin (production behind nginx).
// Local Jetty sets http://127.0.0.1:8080. Override at runtime with ?apiBase=... or by editing this file.
window.__ZJ_API_BASE__ = window.__ZJ_API_BASE__ || "__API_BASE_URL__";
