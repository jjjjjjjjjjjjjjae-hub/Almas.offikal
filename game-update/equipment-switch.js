(function () {
  'use strict';
  if (window.__almasEquipmentSwitchV1) return;
  window.__almasEquipmentSwitchV1 = true;

  // Original switch scheduling and input guard for this game's WeaponSystem.
  // The separate AK runtime samples the CC-BY animation AK_Draw from Cransh's
  // GitHub asset. This controller does not copy or alter that animation or rig.
  const HOLSTER_SECONDS = 0.65;
  const DRAW_SECONDS = 0.65;

  function install() {
    let ws;
    try { ws = weaponSystem; } catch (_) { ws = null; }
    if (!ws || !ws.select || !ws.update || !ws.knifeCombat) {
      requestAnimationFrame(install);
      return;
    }
    if (ws.__equipmentSwitch) return;

    const combat = ws.knifeCombat;
    const originalSelect = ws.select.bind(ws);
    const originalUpdate = ws.update.bind(ws);
    const originalFire = ws.fire && ws.fire.bind(ws);
    const listeners = new Set();
    const state = ws.__equipmentSwitch = {
      phase: 'idle', progress: 1, elapsed: 0,
      from: ws.active, to: ws.active, target: ws.active,
      active: ws.active, switching: false,
      holsterDuration: HOLSTER_SECONDS, drawDuration: DRAW_SECONDS,
      serial: 0,
      subscribe: function (listener) {
        if (typeof listener !== 'function') return function () {};
        listeners.add(listener);
        return function () { listeners.delete(listener); };
      },
      cancel: function (reason) { cancel(reason || 'cancel'); }
    };

    function blocked() { return state.phase !== 'idle'; }

    function report(type, reason) {
      state.active = ws.active;
      state.switching = blocked();
      const detail = {
        type: type, reason: reason || '', serial: state.serial,
        phase: state.phase, progress: state.progress,
        from: state.from, to: state.to, target: state.target,
        active: ws.active, switching: state.switching
      };
      if (window.gameDiagnostics) {
        window.gameDiagnostics.equipmentSwitch = detail;
        window.gameDiagnostics.equipmentSwitchPhase = state.phase;
        window.gameDiagnostics.equipmentSwitchProgress = state.progress;
        window.gameDiagnostics.equipmentSwitchActive = ws.active;
        window.gameDiagnostics.equipmentSwitchFrom = state.from;
        window.gameDiagnostics.equipmentSwitchTarget = state.target;
      }
      if (type === 'progress') return;
      listeners.forEach(function (listener) {
        // A consumer's renderer must not be able to leave input locked.
        try { listener(detail, ws); } catch (error) {
          if (window.console && console.warn) console.warn('Equipment switch listener failed', error);
        }
      });
      if (typeof window.CustomEvent === 'function' && window.dispatchEvent) {
        window.dispatchEvent(new window.CustomEvent('almas:equipment-switch', { detail: detail }));
      }
    }

    function releaseAction() {
      const pointer = ws.actionPointer;
      ws.release();
      // Original release clears its pointer variable but retains native capture.
      // Releasing capture too prevents a held touch from re-firing the next gun.
      const button = ws.actionButton;
      if (pointer !== null && pointer !== undefined && button && button.hasPointerCapture && button.releasePointerCapture) {
        try {
          if (button.hasPointerCapture(pointer)) button.releasePointerCapture(pointer);
        } catch (_) { /* Pointer may have ended on the same frame. */ }
      }
      ws.fireHeld = false;
      combat.held = false;
    }

    function stopKnifeAttack() {
      combat.elapsed = -1;
      combat.hitDone = true;
      combat.hitTime = 0;
      if (combat.contact) combat.contact.visible = false;
    }

    function begin(kind) {
      releaseAction();
      stopKnifeAttack();
      // WeaponSystem.select used to cancel an in-progress reload immediately.
      // Preserve that behavior, rather than refilling a holstering gun later.
      ws.reloadTime = 0;
      state.phase = 'holster';
      state.progress = 0;
      state.elapsed = 0;
      state.from = ws.active;
      state.to = kind;
      state.target = kind;
      state.serial++;
      if (ws.refreshHud) ws.refreshHud();
      report('start');
    }

    function cancel(reason) {
      if (!blocked()) { releaseAction(); return; }
      releaseAction();
      stopKnifeAttack();
      state.phase = 'idle';
      state.progress = 1;
      state.elapsed = 0;
      state.to = ws.active;
      state.target = ws.active;
      report('cancel', reason);
    }

    function commit() {
      // Resolve the latest request at the invisible midpoint. Never call our
      // wrapped select here: that would start another holster cycle.
      const target = ws.owned.has(state.target) ? state.target : ws.active;
      const savedCooldown = ws.switchCooldown;
      const previous = ws.active;
      ws.switchCooldown = 0;
      try {
        originalSelect(target);
      } catch (error) {
        ws.switchCooldown = savedCooldown;
        if (window.gameDiagnostics) window.gameDiagnostics.equipmentSwitchError = String(error);
        cancel('selection-error');
        return;
      }
      // Successful different-weapon selection supplies the original 0.14s
      // cooldown. A same-weapon request must not discard an existing cooldown.
      if (ws.active === previous) ws.switchCooldown = savedCooldown;
      state.to = ws.active;
      state.target = ws.active;
      state.phase = 'draw';
      state.progress = 0;
      state.elapsed = 0;
      releaseAction();
      stopKnifeAttack();
      report('selected');
    }

    function complete() {
      const next = state.target;
      state.phase = 'idle';
      state.progress = 1;
      state.elapsed = 0;
      releaseAction();
      report('complete');
      if (next !== ws.active && ws.owned.has(next)) begin(next);
      else { state.target = ws.active; state.to = ws.active; report('progress'); }
    }

    ws.select = function (kind) {
      if (!this.owned.has(kind)) return false;
      if (blocked()) {
        // A holster has no committed destination yet, so replace it directly.
        // During draw retain the pose destination and queue the next request.
        state.target = kind;
        if (state.phase === 'holster') state.to = kind;
        releaseAction();
        report('target');
        return true;
      }
      if (this.active === kind || this.switchCooldown > 0) return true;
      begin(kind);
      return true;
    };

    if (originalFire) ws.fire = function () {
      if (blocked()) { releaseAction(); return false; }
      return originalFire.apply(null, arguments);
    };

    // The existing action button calls KnifeCombat.press directly, independent
    // of WeaponSystem.fire. Guard all melee entry points, including held attack
    // retries and contact checks from a slash started before the switch.
    ['press', 'attack', 'checkContact'].forEach(function (name) {
      if (typeof combat[name] !== 'function') return;
      const original = combat[name].bind(combat);
      combat[name] = function () {
        if (blocked()) { combat.held = false; return false; }
        return original.apply(null, arguments);
      };
    });

    if (ws.actionButton && ws.actionButton.addEventListener) {
      ws.actionButton.addEventListener('pointerdown', function (event) {
        if (!blocked()) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        releaseAction();
      }, true);
      ['pointercancel', 'lostpointercapture'].forEach(function (name) {
        ws.actionButton.addEventListener(name, function () {
          // Cancelling a touch only releases the action; it must not interrupt
          // or restart the switch and accidentally enable firing midway.
          releaseAction();
        }, true);
      });
    }
    window.addEventListener('blur', function () { cancel('blur'); });
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) cancel('hidden');
    });

    ws.update = function (dt) {
      let remaining = Number.isFinite(dt) ? Math.max(0, dt) : 0;
      // Split a long frame at phase boundaries so reload/cooldown updates run
      // against the correct weapon, without skipping or restarting phases.
      do {
        if (!blocked()) { originalUpdate(remaining); break; }
        releaseAction();
        const duration = state.phase === 'holster' ? HOLSTER_SECONDS : DRAW_SECONDS;
        const step = Math.min(remaining, Math.max(0, duration - state.elapsed));
        originalUpdate(step);
        state.elapsed = Math.min(duration, state.elapsed + step);
        state.progress = Math.max(0, Math.min(1, state.elapsed / duration));
        remaining = Math.max(0, remaining - step);
        report('progress');
        if (state.elapsed + 1e-9 >= duration) {
          if (state.phase === 'holster') commit();
          else complete();
        } else break;
      } while (remaining > 1e-9);
    };

    if (window.gameDiagnostics) {
      Object.assign(window.gameDiagnostics, {
        equipmentSwitchEnabled: true,
        equipmentSwitchAllSlots: true,
        equipmentSwitchFireGuard: true,
        equipmentSwitchHolsterSeconds: HOLSTER_SECONDS,
        equipmentSwitchDrawSeconds: DRAW_SECONDS
      });
    }
    report('installed');
  }
  install();
})();
