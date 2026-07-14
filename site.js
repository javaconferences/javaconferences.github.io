window.addEventListener('DOMContentLoaded', function () {
  // --- Filter ---
  var conferenceFilter = document.querySelector('input[data-filter="conferences"]');
  var conferenceTable = document.getElementById('conferences');
  var conferenceTableTrs = Array.from(conferenceTable.querySelectorAll('tbody > tr'))
    .map(function (e) {
      return { text: (e.innerText || e.textContent).toLowerCase(), element: e, display: e.style.display };
    });
  conferenceFilter.addEventListener('keyup', function () {
    var filter = (conferenceFilter.value || '').toLowerCase().split(' ');
    conferenceTableTrs.forEach(function (data) {
      data.element.style.display = filter.some(function (it) { return data.text.indexOf(it) >= 0; })
        ? data.display
        : 'none';
    });
  });

  // --- Sortable headers ---
  var MONTHS = {
    january: 1, february: 2, march: 3, april: 4, may: 5, june: 6,
    july: 7, august: 8, september: 9, october: 10, november: 11, december: 12,
    jan: 1, feb: 2, mar: 3, apr: 4, jun: 6, jul: 7, aug: 8, sep: 9, sept: 9, oct: 10, nov: 11, dec: 12
  };

  function parseDate(text) {
    // matches "22-27 January 2026", "17–19 March 2026", "26 January 2025"
    var m = text.match(/(\d+)\s*[–\-]?\s*\d*\s*([A-Za-z]+)\s+(\d{4})/);
    if (!m) return Infinity;
    var mo = MONTHS[m[2].toLowerCase()];
    if (!mo) return Infinity;
    return parseInt(m[3], 10) * 10000 + mo * 100 + parseInt(m[1], 10);
  }

  function parseCfpDate(text) {
    // matches "Closes 31 May 2026", "Closed 30 September 2025", "closed November 2025" (no day)
    var m = text.match(/Clos(?:es|ed)\s+(?:(\d+)\s+)?([A-Za-z]+)\s+(\d{4})/i);
    if (!m) return Infinity;
    var mo = MONTHS[m[2].toLowerCase()];
    if (!mo) return Infinity;
    var day = m[1] ? parseInt(m[1], 10) : 1;
    return parseInt(m[3], 10) * 10000 + mo * 100 + day;
  }

  function detectType(headerText) {
    var t = headerText.toLowerCase();
    if (t.indexOf('date') >= 0) return 'date';
    if (t.indexOf('cfp') >= 0) return 'cfp';
    return 'text';
  }

  function getKey(td, type) {
    var text = (td.innerText || td.textContent || '').trim();
    if (type === 'date') return parseDate(text);
    if (type === 'cfp') return parseCfpDate(text);
    return text.toLowerCase();
  }

  function cmpKey(a, b) {
    if (a === b) return 0;
    if (a === Infinity) return 1;
    if (b === Infinity) return -1;
    return a < b ? -1 : 1;
  }

  Array.from(document.querySelectorAll('table')).forEach(function (table) {
    var tbody = table.querySelector('tbody');
    if (!tbody) return;
    var ths = Array.from(table.querySelectorAll('thead th'));
    if (ths.length === 0) return;

    var state = { col: -1, dir: 1 };

    ths.forEach(function (th, idx) {
      th.classList.add('sortable');
      var indicator = document.createElement('span');
      indicator.className = 'sort-indicator';
      indicator.textContent = '⇅';
      th.appendChild(indicator);
      var type = detectType(th.textContent);

      th.addEventListener('click', function () {
        if (state.col === idx) {
          state.dir = -state.dir;
        } else {
          state.col = idx;
          state.dir = 1;
        }

        var rows = Array.from(tbody.querySelectorAll('tr'));
        rows.sort(function (r1, r2) {
          var c1 = r1.cells[idx], c2 = r2.cells[idx];
          if (!c1 || !c2) return 0;
          return cmpKey(getKey(c1, type), getKey(c2, type)) * state.dir;
        });
        rows.forEach(function (r) { tbody.appendChild(r); });

        ths.forEach(function (h, i) {
          var ind = h.querySelector('.sort-indicator');
          if (i === idx) {
            ind.textContent = state.dir > 0 ? '▲' : '▼';
            h.classList.add('sort-active');
          } else {
            ind.textContent = '⇅';
            h.classList.remove('sort-active');
          }
        });
      });
    });
  });
});
