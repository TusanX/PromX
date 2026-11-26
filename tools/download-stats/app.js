(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const sourceNames = { website: "官网", hero: "首屏", latest: "最新版", download: "下载区" };
  const formatDate = (timestamp) => new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
  }).format(new Date(timestamp));
  const sortedEntries = (object) => Object.entries(object || {}).sort((a, b) => Number(b[1]) - Number(a[1]));

  function renderChart(days) {
    const chart = $("chart");
    chart.replaceChildren();
    const entries = Object.entries(days || {}).sort(([a], [b]) => a.localeCompare(b)).slice(-14);
    const max = Math.max(1, ...entries.map(([, value]) => Number(value) || 0));
    for (const [day, count] of entries) {
      const column = document.createElement("div");
      column.className = "chart-day";
      const value = document.createElement("span");
      value.className = "chart-value";
      value.textContent = String(count);
      const bar = document.createElement("i");
      bar.className = "chart-bar";
      bar.style.height = `${Math.max(2, Number(count) / max * 175)}px`;
      const label = document.createElement("span");
      label.className = "chart-label";
      label.textContent = day.slice(5).replace("-", "/");
      column.append(value, bar, label);
      chart.append(column);
    }
  }

  function renderBreakdown(target, object) {
    target.replaceChildren();
    const entries = sortedEntries(object);
    const total = entries.reduce((sum, [, value]) => sum + Number(value || 0), 0) || 1;
    if (!entries.length) {
      const empty = document.createElement("span");
      empty.textContent = "暂无记录";
      empty.style.color = "#68758f";
      empty.style.fontSize = "10px";
      target.append(empty);
      return;
    }
    for (const [name, count] of entries) {
      const row = document.createElement("div");
      row.className = "break-row";
      const copy = document.createElement("div");
      copy.className = "break-copy";
      const label = document.createElement("span");
      label.textContent = name;
      const number = document.createElement("span");
      number.textContent = String(count);
      copy.append(label, number);
      const track = document.createElement("div");
      track.className = "break-track";
      const fill = document.createElement("i");
      fill.style.width = `${Math.max(3, Number(count) / total * 100)}%`;
      track.append(fill);
      row.append(copy, track);
      target.append(row);
    }
  }

  function renderRecent(records) {
    const body = $("recent");
    body.replaceChildren();
    if (!records?.length) {
      const row = document.createElement("tr");
      const cell = document.createElement("td");
      cell.colSpan = 4;
      cell.textContent = "暂无记录";
      row.append(cell);
      body.append(row);
      return;
    }
    for (const record of records) {
      const row = document.createElement("tr");
      for (const value of [formatDate(record.at), record.version, record.platform, sourceNames[record.source] || record.source]) {
        const cell = document.createElement("td");
        cell.textContent = value;
        row.append(cell);
      }
      body.append(row);
    }
  }

  async function loadStats() {
    const button = $("refresh");
    button.disabled = true;
    $("error").textContent = "";
    try {
      const response = await fetch("/api/stats", { cache: "no-store" });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
      $("total").textContent = data.total ?? 0;
      $("today").textContent = data.today ?? 0;
      $("last7").textContent = data.last7 ?? 0;
      $("last30").textContent = data.last30 ?? 0;
      $("updated").textContent = data.updatedAt ? `更新于 ${formatDate(data.updatedAt)}` : "暂无记录";
      renderChart(data.days);
      renderBreakdown($("versions"), data.versions);
      renderBreakdown($("sources"), data.sources);
      renderRecent(data.recent);
    } catch (error) {
      $("error").textContent = error instanceof Error ? error.message : "加载失败";
    } finally {
      button.disabled = false;
    }
  }

  $("refresh").addEventListener("click", loadStats);
  loadStats();
  setInterval(loadStats, 60000);
})();
