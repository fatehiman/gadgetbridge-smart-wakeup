/* Night detail charts. Data comes from the #night-data JSON block. x axis = epoch seconds, formatted in the session time zone. */
(function () {
  var el = document.getElementById('night-data');
  if (!el || !window.Chart) return;
  var d = JSON.parse(el.textContent);
  var fmt = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: d.tz });
  var tick = function (v) { return fmt.format(new Date(v * 1000)); };
  var xmin = d.start, xmax = d.end || (d.points.length ? d.points[d.points.length - 1].ts : d.start + 3600);

  var bands = {
    id: 'bands',
    beforeDatasetsDraw: function (chart) {
      var c = chart.ctx, a = chart.chartArea, x = chart.scales.x;
      c.save();
      if (d.window[0] && d.window[1]) {
        var x1 = x.getPixelForValue(d.window[0]), x2 = x.getPixelForValue(d.window[1]);
        c.fillStyle = 'rgba(251, 239, 198, .75)';
        c.fillRect(x1, a.top, x2 - x1, a.bottom - a.top);
      }
      c.restore();
    },
    afterDatasetsDraw: function (chart) {
      var c = chart.ctx, a = chart.chartArea, x = chart.scales.x;
      var colors = { session_started: '#5f95d2', session_stopped: '#8b94a8', wake_triggered: '#e08a62', wake_acknowledged: '#46a585' };
      c.save();
      d.events.forEach(function (e) {
        var col = colors[e.type];
        if (e.type === 'detector_evaluated') col = e.decision === 'wake' || e.decision === 'WakeNow' ? '#d9708f' : 'rgba(138,120,214,.25)';
        if (!col || e.ts < x.min || e.ts > x.max) return;
        var px = x.getPixelForValue(e.ts);
        c.strokeStyle = col; c.lineWidth = e.type === 'detector_evaluated' ? 1 : 2;
        c.setLineDash(e.type === 'detector_evaluated' ? [2, 3] : []);
        c.beginPath(); c.moveTo(px, a.top); c.lineTo(px, a.bottom); c.stroke();
      });
      c.restore();
    }
  };

  function scales(yTitle) {
    return {
      x: { type: 'linear', min: xmin, max: xmax, ticks: { callback: tick, maxTicksLimit: 10 }, grid: { color: '#eef1f7' } },
      y: { title: { display: true, text: yTitle }, grid: { color: '#eef1f7' } }
    };
  }
  var pts = function (key) {
    return d.points.filter(function (p) { return p[key] !== null; }).map(function (p) { return { x: p.ts, y: p[key] }; });
  };
  var tip = { callbacks: { title: function (items) { return tick(items[0].parsed.x); } } };

  new Chart(document.getElementById('chart-hr'), {
    type: 'line',
    data: { datasets: [
      { label: 'Heart rate (bpm)', data: pts('hr'), borderColor: '#e08a62', backgroundColor: '#fde2d4', pointRadius: 0, borderWidth: 2, tension: .2 },
      { label: 'SpO2 (%)', data: pts('spo2'), borderColor: '#5f95d2', pointRadius: 0, borderWidth: 1.5, yAxisID: 'y2', hidden: pts('spo2').length === 0 }
    ] },
    options: { maintainAspectRatio: false, animation: false, parsing: false, plugins: { tooltip: tip },
      scales: Object.assign(scales('bpm'), { y2: { position: 'right', min: 80, max: 100, grid: { display: false }, display: pts('spo2').length > 0 } }) },
    plugins: [bands]
  });
  new Chart(document.getElementById('chart-mov'), {
    type: 'bar',
    data: { datasets: [{ label: 'Movement / intensity', data: pts('mov'), backgroundColor: '#8a78d6', borderWidth: 0, barThickness: 3 }] },
    options: { maintainAspectRatio: false, animation: false, parsing: false, plugins: { tooltip: tip }, scales: scales('movement') },
    plugins: [bands]
  });
})();
