(() => {
  "use strict";

  const MANIFEST_URL = "https://prom-study-1522632969.cos.ap-guangzhou.myqcloud.com/downloads/latest.json";
  const TRACK_URL = "https://1522632969-6f39qidpa3.ap-guangzhou.tencentscf.com/downloads/track";
  const button = document.getElementById("startDownload");
  const status = document.getElementById("downloadStatus");
  const progressBar = document.getElementById("progressBar");
  const versionLabel = document.getElementById("releaseVersion");
  const buildLabel = document.getElementById("releaseBuild");
  const sizeLabel = document.getElementById("releaseSize");
  const dateLabel = document.getElementById("releaseDate");
  const packageVersion = document.getElementById("packageVersion");
  const packageSize = document.getElementById("packageSize");
  const packageSha = document.getElementById("packageSha");
  let manifest = null;
  let downloadedBytes = 0;
  let running = false;
  let loadingManifest = false;
  let manifestLoadedAt = 0;

  const totalOf = (parts) => parts.reduce((total, part) => total + part.size, 0);
  const formatSize = (bytes) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  const setButtonLabel = (text) => {
    const label = button?.querySelector("span");
    if (label) label.textContent = text;
  };
  const setStatus = (text) => {
    if (status) status.textContent = text;
  };
  const setProgress = () => {
    if (!manifest || !progressBar) return;
    const available = manifest.parts.reduce((sum, part) => sum + part.size, 0);
    const percent = Math.min(100, Math.round(downloadedBytes / available * 100));
    progressBar.style.width = `${percent}%`;
    setStatus(`正在从腾讯云下载 ${formatSize(downloadedBytes)} / ${formatSize(available)} · ${percent}%`);
  };

  function validateManifest(raw) {
    if (!raw || !/^\d+\.\d+\.\d+$/.test(String(raw.version || ""))) return null;
    if (!/^[a-f0-9]{64}$/i.test(String(raw.sha256 || ""))) return null;
    if (!Number.isSafeInteger(raw.size) || raw.size <= 0 || !Array.isArray(raw.parts) || raw.parts.length === 0) return null;
    const parts = raw.parts.filter((part) =>
      typeof part?.url === "string" &&
      part.url.startsWith("https://prom-study-1522632969.cos.ap-guangzhou.myqcloud.com/") &&
      Number.isSafeInteger(part.size) && part.size > 0);
    if (parts.length !== raw.parts.length || totalOf(parts) !== raw.size) return null;
    return { ...raw, sha256: raw.sha256.toLowerCase(), parts };
  }

  function applyManifest(next) {
    manifest = next;
    manifestLoadedAt = Date.now();
    const build = String(next.build || "release");
    const published = Number.isFinite(Date.parse(next.publishedAt))
      ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(next.publishedAt))
      : "—";
    if (versionLabel) versionLabel.textContent = next.version;
    if (buildLabel) buildLabel.textContent = build;
    if (sizeLabel) sizeLabel.textContent = formatSize(next.size);
    if (dateLabel) dateLabel.textContent = published;
    if (packageVersion) packageVersion.textContent = `${next.version} · ${build}`;
    if (packageSize) packageSize.textContent = formatSize(next.size);
    if (packageSha) packageSha.textContent = next.sha256;
    if (button) {
      button.disabled = false;
      setButtonLabel("下载最新版 APK");
    }
    setStatus(`已读取最新版本 ${next.version}，可以开始下载`);
  }

  async function loadLatestManifest() {
    if (!button || loadingManifest) return false;
    loadingManifest = true;
    manifest = null;
    button.disabled = true;
    setButtonLabel("正在读取最新版本");
    setStatus("正在连接腾讯云版本清单");
    try {
      const endpoint = `${MANIFEST_URL}?_=${Date.now()}`;
      const response = await fetch(endpoint, {
        cache: "no-store",
        mode: "cors",
        headers: { "Cache-Control": "no-cache", Pragma: "no-cache" },
      });
      if (!response.ok) throw new Error(`版本清单请求失败 (${response.status})`);
      const next = validateManifest(await response.json());
      if (!next) throw new Error("最新版本清单格式不正确");
      applyManifest(next);
      return true;
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "无法读取最新版本");
      button.disabled = false;
      setButtonLabel("重新获取最新版本");
      return false;
    } finally {
      loadingManifest = false;
    }
  }

  async function readPart(part, index) {
    const response = await fetch(part.url, { cache: "force-cache", mode: "cors" });
    if (!response.ok) throw new Error(`腾讯云分片 ${index + 1} 下载失败`);
    const expectedLength = part.size;
    if (!response.body?.getReader) {
      const bytes = new Uint8Array(await response.arrayBuffer());
      if (bytes.byteLength !== expectedLength) throw new Error(`腾讯云分片 ${index + 1} 长度不一致`);
      downloadedBytes += bytes.byteLength;
      setProgress();
      return bytes;
    }

    const reader = response.body.getReader();
    const chunks = [];
    let received = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      received += value.byteLength;
      downloadedBytes += value.byteLength;
      setProgress();
    }
    if (received !== expectedLength) throw new Error(`腾讯云分片 ${index + 1} 长度不一致`);
    const bytes = new Uint8Array(received);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    return bytes;
  }

  async function digestHex(buffer) {
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", buffer));
    return Array.from(digest, (value) => value.toString(16).padStart(2, "0")).join("");
  }

  function trackCompletedDownload() {
    const eventId = crypto.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    const source = new URLSearchParams(location.search).get("source") || "website";
    fetch(TRACK_URL, {
      method: "POST",
      mode: "cors",
      cache: "no-store",
      keepalive: true,
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ version: `${manifest.version}-${manifest.build || "release"}`, source, eventId }),
    }).catch(() => {});
  }

  async function startDownload() {
    if (running) return;
    if (!manifest) {
      await loadLatestManifest();
      return;
    }
    running = true;
    downloadedBytes = 0;
    button.disabled = true;
    setButtonLabel("正在下载");
    setProgress();

    try {
      const buffers = await Promise.all(manifest.parts.map(readPart));
      setStatus("正在校验安装包");
      const mergedSize = buffers.reduce((total, bytes) => total + bytes.byteLength, 0);
      if (mergedSize !== manifest.size) throw new Error("合并后的安装包大小不一致");
      const bytes = new Uint8Array(mergedSize);
      let offset = 0;
      for (const buffer of buffers) {
        bytes.set(buffer, offset);
        offset += buffer.byteLength;
      }
      const digest = await digestHex(bytes);
      if (digest !== manifest.sha256) throw new Error("SHA-256 校验失败，请重新下载");

      const blob = new Blob([bytes], { type: "application/vnd.android.package-archive" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      const safeName = String(manifest.apkName || `PromX-${manifest.version}.apk`).replace(/[\\/:*?"<>|]+/g, "-");
      link.href = url;
      link.download = safeName.endsWith(".apk") ? safeName : `${safeName}.apk`;
      document.body.append(link);
      link.click();
      link.remove();
      trackCompletedDownload();
      setTimeout(() => URL.revokeObjectURL(url), 120000);
      progressBar.style.width = "100%";
      setStatus("下载完成，SHA-256 校验通过");
      setButtonLabel("重新下载最新版");
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "下载失败，请重试");
      setButtonLabel("重试下载最新版");
    } finally {
      running = false;
      button.disabled = false;
    }
  }

  button?.addEventListener("click", () => {
    if (!manifest || Date.now() - manifestLoadedAt > 15_000) {
      loadLatestManifest().then((loaded) => { if (loaded) startDownload(); });
    } else startDownload();
  });

  window.addEventListener("load", async () => {
    const loaded = await loadLatestManifest();
    if (loaded && new URLSearchParams(location.search).get("auto") === "1") {
      setTimeout(startDownload, 240);
    }
  }, { once: true });

  window.addEventListener("pageshow", (event) => {
    if (event.persisted) loadLatestManifest();
  });
})();
