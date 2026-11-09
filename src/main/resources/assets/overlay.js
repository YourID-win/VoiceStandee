/* Draws the people and their movement. Used by the OBS page (it boots itself) and by the preview in the settings page. */
(function (global) {
  'use strict';

  var ALIGN = { left: 'flex-start', center: 'center', right: 'flex-end' };

  /**
   * @param stage       the element the people are put into
   * @param varsTarget  the element whose CSS variables carry the settings (the page for OBS, the preview frame for settings)
   */
  function create(stage, varsTarget) {
    var nodes = new Map();
    var anim = 'hop';

    function setTalking(el, on) {
      var fig = el.firstChild;
      el.classList.toggle('talk', on);
      fig.dataset.want = on ? '1' : '0';
      if (on && anim !== 'none') fig.classList.add('a-' + anim);
    }

    function apply(s) {
      var st = varsTarget.style;
      st.setProperty('--h', s.h + 'px');
      st.setProperty('--gap', s.gap + 'px');
      st.setProperty('--idle', s.idle);
      st.setProperty('--align', ALIGN[s.align] || 'center');
      st.setProperty('--hop', s.hopHeight / 100);
      st.setProperty('--speed', s.hopSpeed + 's');
      st.setProperty('--squash', s.squash / 100);
      st.setProperty('--tilt', s.tilt + 'deg');
      stage.classList.toggle('names', !!s.names);
      if (s.anim !== anim) {
        // only a change of kind restarts the movement; the numbers above take effect while it keeps running
        anim = s.anim;
        nodes.forEach(function (el) {
          var fig = el.firstChild;
          fig.classList.remove('a-hop', 'a-pulse');
          if (el.classList.contains('talk') && anim !== 'none') fig.classList.add('a-' + anim);
        });
      }
    }

    function make(p) {
      var el = document.createElement('div');
      el.className = 'p';
      el.dataset.id = p.id;
      var fig = document.createElement('div');
      fig.className = 'fig';
      var img = document.createElement('img');
      img.alt = '';
      img.decoding = 'async';
      fig.appendChild(img);
      var name = document.createElement('div');
      name.className = 'name';
      el.appendChild(fig);
      el.appendChild(name);
      stage.appendChild(el);
      requestAnimationFrame(function () { requestAnimationFrame(function () { el.classList.add('in'); }); });
      return el;
    }

    function update(el, p) {
      var img = el.querySelector('img');
      var src = p.img || p.avatar || '';
      if (img.dataset.src !== src) { img.dataset.src = src; img.src = src; }
      img.classList.toggle('avatar', !p.img);
      el.querySelector('.name').textContent = p.name;
      setTalking(el, !!p.speaking);
    }

    // The movement stops only at the end of a cycle, so that it settles instead of snapping back.
    stage.addEventListener('animationiteration', function (e) {
      var fig = e.target;
      if (fig.dataset && fig.dataset.want === '0') fig.classList.remove('a-hop', 'a-pulse');
    });

    function render(state) {
      var people = state.people || [];
      var seen = new Set();
      people.forEach(function (p) {
        seen.add(p.id);
        var el = nodes.get(p.id);
        if (!el) { el = make(p); nodes.set(p.id, el); }
        el.classList.remove('out');
        update(el, p);
      });
      var prev = null;
      people.forEach(function (p) {
        var el = nodes.get(p.id);
        var expected = prev ? prev.nextElementSibling : stage.firstElementChild;
        if (el !== expected) stage.insertBefore(el, expected);
        prev = el;
      });
      nodes.forEach(function (el, id) {
        if (seen.has(id) || el.classList.contains('out')) return;
        el.classList.add('out');
        setTimeout(function () {
          if (el.classList.contains('out')) { el.remove(); if (nodes.get(id) === el) nodes.delete(id); }
        }, 400);
      });
    }

    return { apply: apply, render: render };
  }

  /** Listens to the live feed of one streamer; basePath is the overlay address without a trailing part, e.g. /o/<secret>. */
  function connect(basePath, onState) {
    var es = new EventSource(basePath + '/events');
    es.onmessage = function (ev) { try { onState(JSON.parse(ev.data)); } catch (err) { /* ignore a broken message */ } };
    es.onerror = function () {
      // the browser retries by itself; only a refused connection (closed for good) needs a fresh start
      if (es.readyState === 2) { es.close(); setTimeout(function () { connect(basePath, onState); }, 5000); }
    };
  }

  global.StandeeOverlay = { create: create, connect: connect };

  if (document.body.classList.contains('overlay')) {
    var overlay = create(document.getElementById('stage'), document.documentElement);
    connect(location.pathname.replace(/\/+$/, ''), function (state) {
      if (state.settings) overlay.apply(state.settings);
      overlay.render(state);
    });
  }
})(window);
