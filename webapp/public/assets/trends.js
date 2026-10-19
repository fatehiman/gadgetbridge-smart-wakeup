/* Trend charts. Data comes from the #trend-data JSON block (one point per night). */
(function () {
  var el = document.getElementById('trend-data');
  if (!el || !window.Chart) return;
  var d = JSON.parse(el.textContent), P = d.points;
  var labels = P.map(function (p) { return p.label; });
  var hhmm = function (m) { return Math.floor(m / 60) + ':' + ('0' + Math.round(m % 60)).slice(-2); };
  var common = { maintainAspectRatio: false, animation: false };
  var grid = { color: '#eef1f7' };

  new Chart(document.getElementById('t-wake'), {
    type: 'line',
    data: { labels: labels, datasets: [
      { label: 'Woke at', data: P.map(function (p) { return p.wake_min; }), borderColor: '#e08a62', backgroundColor: '#fde2d4', tension: .2 },
      { label: 'Target', data: P.map(function (p) { return p.target_min; }), borderColor: '#5f95d2', borderDash: [5, 4], pointRadius: 0 }
    ] },
    options: Object.assign({}, common, { scales: { y: { grid: grid, ticks: { callback: hhmm } }, x: { grid: grid } },
      plugins: { tooltip: { callbacks: { label: function (c) { return c.dataset.label + ': ' + hhmm(c.parsed.y); } } } } })
  });

  var keys = Object.keys(d.reasons);
  var pal = ['#46a585', '#e08a62', '#8a78d6', '#c99a24', '#d9708f', '#5f95d2'];
  new Chart(document.getElementById('t-reasons'), {
    type: 'doughnut',
    data: { labels: keys, datasets: [{ data: keys.map(function (k) { return d.reasons[k]; }), backgroundColor: keys.map(function (k, i) { return pal[i % pal.length]; }) }] },
    options: common
  });

  new Chart(document.getElementById('t-hr'), {
    type: 'line',
    data: { labels: labels, datasets: [{ label: 'Avg HR (bpm)', data: P.map(function (p) { return p.avg_hr; }), borderColor: '#d9708f', backgroundColor: '#fbdfe7', tension: .2 }] },
    options: Object.assign({}, common, { scales: { y: { grid: grid }, x: { grid: grid } } })
  });
  new Chart(document.getElementById('t-dur'), {
    type: 'bar',
    data: { labels: labels, datasets: [{ label: 'Hours', data: P.map(function (p) { return p.hours; }), backgroundColor: '#8bb9ee' }] },
    options: Object.assign({}, common, { scales: { y: { grid: grid, beginAtZero: true }, x: { grid: grid } } })
  });
})();
