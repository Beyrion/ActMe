(function () {
  function textOf(node) {
    return node ? (node.innerText || node.textContent || "").replace(/\s+/g, " ").trim() : "";
  }

  function pageText() {
    return textOf(document.body).slice(0, 16000);
  }

  function sendPageContent(reason) {
    try {
      browser.runtime.sendNativeMessage("actme_gecko_search", {
        type: "rendered_page",
        reason: reason,
        pageTitle: document.title || "",
        pageUrl: location.href,
        text: pageText()
      });
    } catch (error) {
      // No-op. GeckoView logs native messaging failures separately.
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () {
      sendPageContent("domcontentloaded");
    }, { once: true });
  } else {
    sendPageContent("ready");
  }

  window.setTimeout(function () { sendPageContent("delay-1500"); }, 1500);
  window.setTimeout(function () { sendPageContent("delay-3500"); }, 3500);
})();
