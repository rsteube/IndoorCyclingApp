// Indoor Bike - in-ride HUD + summary

function RideScreen({ workout, onEnd }) {
  const [t, setT] = React.useState(0);
  const [paused, setPaused] = React.useState(false);
  React.useEffect(() => { if (window.lucide) window.lucide.createIcons(); });
  React.useEffect(() => {
    if (paused) return;
    const id = setInterval(() => setT(x => x + 1), 1000);
    return () => clearInterval(id);
  }, [paused]);

  const segs = workout.segs;
  const total = segs.reduce((s,x) => s + x.weight, 0);
  const tNorm = (t * 0.4) % total;
  let cum = 0; let curIdx = 0; let stepRemain = 0;
  for (let i = 0; i < segs.length; i++) {
    if (tNorm < cum + segs[i].weight) { curIdx = i; stepRemain = (cum + segs[i].weight - tNorm); break; }
    cum += segs[i].weight;
  }
  const curZone = segs[curIdx].zone;
  const targetW = Math.round([0.5,0.65,0.83,0.98,1.13,1.35,1.6][curZone-1] * 248);
  const liveW = targetW + Math.round(Math.sin(t / 1.7) * 4);
  const cad = 88 + Math.round(Math.sin(t / 0.9) * 4);
  const hr = 142 + Math.round(Math.sin(t / 5) * 8) + (curZone - 2) * 6;
  const progress = tNorm / total;

  const mmss = sec => `${String(Math.floor(sec/60)).padStart(2,'0')}:${String(Math.floor(sec%60)).padStart(2,'0')}`;
  const elapsed = mmss(tNorm * 60);
  const stepLeft = mmss(stepRemain * 60);

  // Build a horizon ridge SVG path: each segment becomes a peak whose height
  // scales with its zone intensity. The ridge "rises" with effort.
  const W = 390, H = 160;
  const peakH = z => [10, 28, 50, 78, 100, 130, 150][z - 1];
  const segPx = total === 0 ? 0 : W / total;
  let xCur = 0;
  const points = [`0,${H}`];
  segs.forEach((s) => {
    const peakX = xCur + (s.weight * segPx) / 2;
    const peakY = H - peakH(s.zone);
    const endX = xCur + s.weight * segPx;
    points.push(`${peakX},${peakY}`);
    points.push(`${endX},${H - peakH(s.zone) * 0.4}`);
    xCur = endX;
  });
  points.push(`${W},${H}`);
  const ridgePath = `M ${points.join(' L ')} Z`;
  const progressX = progress * W;

  return (
    <div style={{ position: 'relative', height: '100%', overflow: 'hidden', background: C.bg0,
      display: 'flex', flexDirection: 'column' }}>
      {/* Tilted horizon ridge backdrop - fills bottom 50% of HUD */}
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 110, height: 200, pointerEvents: 'none', zIndex: 0 }}>
        <svg width="100%" height="100%" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" style={{ display: 'block' }}>
          <defs>
            <linearGradient id="ridgeFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={C.z[curZone-1]} stopOpacity="0.55"/>
              <stop offset="100%" stopColor={C.z[curZone-1]} stopOpacity="0.04"/>
            </linearGradient>
          </defs>
          {/* far ridge - pale, behind */}
          <path d={ridgePath} fill={C.bg2} opacity="0.5" transform="translate(0,18) scale(1,0.7)"/>
          {/* near ridge - tinted by current zone */}
          <path d={ridgePath} fill="url(#ridgeFill)"/>
          <path d={ridgePath} fill="none" stroke={C.z[curZone-1]} strokeWidth="1.5"/>
          {/* progress marker */}
          <line x1={progressX} y1="0" x2={progressX} y2={H} stroke={C.brand} strokeWidth="2" strokeDasharray="3 3"/>
          <circle cx={progressX} cy={H - peakH(curZone) * 0.7} r="5" fill={C.brand} stroke={C.bg0} strokeWidth="2"/>
        </svg>
      </div>

      {/* Top status row */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '14px 20px', position: 'relative', zIndex: 2 }}>
        <ZoneBadge z={curZone} label={['RECOVERY','ENDURANCE','TEMPO','THRESHOLD','VO2','ANAEROBIC','NEURO'][curZone-1]} />
        <div style={{ fontFamily: FF.mono, fontSize: 12, color: C.fg2, letterSpacing: '0.06em' }}>{elapsed} / {mmss(total * 60)}</div>
      </div>

      {/* Main power readout */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', position: 'relative', zIndex: 2, gap: 8, paddingBottom: 40 }}>
        <div style={{ fontFamily: FF.body, fontWeight: 700, fontSize: 11, color: C.fg3, textTransform: 'uppercase', letterSpacing: '0.18em' }}>POWER · TARGET {targetW} W</div>
        <div style={{ fontFamily: FF.display, fontSize: 132, lineHeight: 0.92, color: C.fg1, letterSpacing: '0.01em', fontFeatureSettings: '"tnum"' }}>
          {liveW}
        </div>
        <div style={{ fontFamily: FF.body, fontWeight: 700, fontSize: 14, color: C.fg2, letterSpacing: '0.18em', textTransform: 'uppercase' }}>WATTS · {Math.round(liveW/248*100)}% FTP</div>
        <div style={{ fontFamily: FF.mono, fontSize: 11, color: C.brand, letterSpacing: '0.08em', marginTop: 4, textTransform: 'uppercase' }}>● ERG LOCKED</div>
      </div>

      {/* Secondary metrics */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 1, background: C.line1, position: 'relative', zIndex: 2 }}>
        {[
          { l: 'CADENCE', v: cad, u: 'RPM' },
          { l: 'HEART', v: hr, u: 'BPM' },
          { l: 'STEP LEFT', v: stepLeft, u: '' },
        ].map((m,i) => (
          <div key={i} style={{ background: C.bg0, padding: '14px 12px', textAlign: 'center' }}>
            <div style={{ fontFamily: FF.body, fontWeight: 700, fontSize: 10, color: C.fg3, letterSpacing: '0.16em' }}>{m.l}</div>
            <div style={{ fontFamily: FF.display, fontSize: 30, color: C.fg1, marginTop: 4, fontFeatureSettings: '"tnum"', letterSpacing: '0.02em' }}>{m.v}<span style={{ fontSize: 11, color: C.fg3, marginLeft: 4, fontFamily: FF.body, fontWeight: 700, letterSpacing: '0.1em' }}>{m.u}</span></div>
          </div>
        ))}
      </div>

      {/* Step indicator strip */}
      <div style={{ padding: '12px 20px 8px', position: 'relative', zIndex: 2, display: 'flex', justifyContent: 'space-between', fontFamily: FF.mono, fontSize: 10, color: C.fg3, letterSpacing: '0.08em' }}>
        <span>STEP {curIdx + 1}/{segs.length}</span>
        <span>UP NEXT · Z{segs[(curIdx+1) % segs.length].zone}</span>
      </div>

      {/* Controls */}
      <div style={{ display: 'flex', gap: 10, padding: '4px 20px 16px', position: 'relative', zIndex: 2 }}>
        <SecondaryBtn onClick={() => setPaused(p => !p)}><i data-lucide={paused ? 'play' : 'pause'} style={{width:16,height:16}}></i>{paused ? 'Resume' : 'Pause'}</SecondaryBtn>
        <SecondaryBtn full><i data-lucide="skip-forward" style={{width:16,height:16}}></i>Skip step</SecondaryBtn>
        <button onClick={onEnd} style={{
          background: 'transparent', color: C.z[5], border: `1px solid rgba(235,59,59,0.4)`,
          borderRadius: 14, padding: '13px 16px', cursor: 'pointer',
          fontFamily: FF.body, fontWeight: 700, fontSize: 14, letterSpacing: '0.04em',
        }}>End</button>
      </div>
    </div>
  );
}

function SummaryScreen({ onDone }) {
  React.useEffect(() => { if (window.lucide) window.lucide.createIcons(); });
  const distribution = [
    { z: 1, mins: 4 }, { z: 2, mins: 11 }, { z: 3, mins: 6 },
    { z: 4, mins: 28 }, { z: 5, mins: 2 }, { z: 6, mins: 1 },
  ];
  const totalMins = 52;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ padding: '32px 24px 12px', textAlign: 'center' }}>
        <div style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 11, color: C.brand, textTransform: 'uppercase', letterSpacing: '0.16em' }}>Workout complete</div>
        <div style={{ fontFamily: FF.display, fontWeight: 700, fontSize: 30, color: C.fg1, marginTop: 8, letterSpacing: '0.02em', textTransform: 'uppercase' }}>Sweet Spot 3×12</div>
        <div style={{ fontFamily: FF.mono, fontSize: 12, color: C.fg3, marginTop: 6, letterSpacing: '0.06em' }}>52:08 · TUE 6 MAY</div>
      </div>

      <div style={{ padding: '0 20px', display: 'flex', flexDirection: 'column', gap: 14, flex: 1 }}>
        {/* Hero stats */}
        <div style={{ background: C.bg1, border: `1px solid ${C.line2}`, borderRadius: 14, padding: 18, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          {[
            { l: 'TSS', v: '64' },
            { l: 'AVG POWER', v: '226', u: 'W' },
            { l: 'NORMALIZED', v: '241', u: 'W' },
            { l: 'kJ', v: '703' },
            { l: 'AVG HR', v: '154', u: 'bpm' },
            { l: 'AVG CADENCE', v: '89', u: 'rpm' },
          ].map((s,i) => (
            <div key={i}>
              <div style={{ fontFamily: FF.mono, fontSize: 10, color: C.fg3, letterSpacing: '0.08em' }}>{s.l}</div>
              <div style={{ fontFamily: FF.display, fontWeight: 700, fontSize: 24, color: C.fg1, marginTop: 2, fontFeatureSettings: '"tnum"' }}>{s.v}<span style={{ fontSize: 12, color: C.fg3, marginLeft: 4, fontWeight: 500 }}>{s.u || ''}</span></div>
            </div>
          ))}
        </div>

        {/* Zone distribution */}
        <div style={{ background: C.bg1, border: `1px solid ${C.line2}`, borderRadius: 14, padding: 18 }}>
          <div style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 11, color: C.fg3, textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 14 }}>Time in zone</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {distribution.map(d => (
              <div key={d.z} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <div style={{ width: 26, fontFamily: FF.mono, fontSize: 11, color: C.fg2, fontWeight: 600 }}>Z{d.z}</div>
                <div style={{ flex: 1, height: 8, background: C.bg0, borderRadius: 999, overflow: 'hidden' }}>
                  <div style={{ width: `${d.mins/totalMins*100}%`, height: '100%', background: C.z[d.z-1] }} />
                </div>
                <div style={{ width: 50, textAlign: 'right', fontFamily: FF.mono, fontSize: 11, color: C.fg2 }}>{d.mins}:00</div>
              </div>
            ))}
          </div>
        </div>

        {/* Saved file row */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px', background: C.bg1, border: `1px solid ${C.line1}`, borderRadius: 12 }}>
          <div style={{ width: 36, height: 36, borderRadius: 10, background: 'rgba(31,179,115,0.16)', color: C.z[2], display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <i data-lucide="check" style={{width:18,height:18}}></i>
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 13, color: C.fg1 }}>Saved locally</div>
            <div style={{ fontFamily: FF.mono, fontSize: 10, color: C.fg3, letterSpacing: '0.04em' }}>2026-05-06_sweet-spot.fit · 18 KB</div>
          </div>
          <i data-lucide="share-2" style={{width:18,height:18,color:C.fg3}}></i>
        </div>
      </div>

      <div style={{ padding: '14px 20px 16px' }}>
        <PrimaryBtn full onClick={onDone}>Done</PrimaryBtn>
      </div>
    </div>
  );
}

// Progress / profile screen
function ProgressScreen({ onTab }) {
  React.useEffect(() => { if (window.lucide) window.lucide.createIcons(); });
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <AppBar title="Progress" eyebrow="Local · last 4 weeks" trailing={<IconBtn name="more-vertical" />} />
      <div style={{ flex: 1, padding: '0 20px 20px', display: 'flex', flexDirection: 'column', gap: 14 }}>

        {/* Streak hero */}
        <div style={{ background: C.bg1, border: `1px solid ${C.line2}`, borderRadius: 14, padding: 20, position: 'relative', overflow: 'hidden' }}>
          <div style={{ position: 'absolute', right: -30, top: -20, width: 160, height: 160, borderRadius: '50%', background: 'radial-gradient(circle, rgba(244,122,31,0.18), transparent 70%)' }} />
          <div style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 11, color: C.fg3, textTransform: 'uppercase', letterSpacing: '0.12em' }}>Current streak</div>
          <div style={{ fontFamily: FF.display, fontWeight: 700, fontSize: 56, color: C.fg1, marginTop: 4, letterSpacing: '0.02em', textTransform: 'uppercase' }}>
            12<span style={{ fontSize: 18, fontWeight: 500, color: C.fg3, marginLeft: 6 }}>days</span>
          </div>
          <div style={{ display: 'flex', gap: 4, marginTop: 14 }}>
            {Array.from({length: 14}).map((_,i) => (
              <div key={i} style={{
                flex: 1, height: 34, borderRadius: 4,
                background: i < 12 ? C.brand : C.bg3,
                opacity: i < 12 ? 0.4 + i * 0.05 : 1,
              }} />
            ))}
          </div>
        </div>

        {/* Level / XP */}
        <div style={{ background: C.bg1, border: `1px solid ${C.line2}`, borderRadius: 14, padding: 18 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
            <div>
              <div style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 11, color: C.fg3, textTransform: 'uppercase', letterSpacing: '0.1em' }}>Rider level</div>
              <div style={{ fontFamily: FF.display, fontWeight: 700, fontSize: 28, color: C.fg1, marginTop: 4 }}>Threshold IV</div>
            </div>
            <div style={{ fontFamily: FF.mono, fontSize: 12, color: C.brand }}>1,840 / 2,400 kJ</div>
          </div>
          <div style={{ height: 6, background: C.bg0, borderRadius: 999, marginTop: 12, overflow: 'hidden' }}>
            <div style={{ width: '76%', height: '100%', background: `linear-gradient(90deg, ${C.brand}, ${C.azure})` }} />
          </div>
          <div style={{ fontFamily: FF.mono, fontSize: 10, color: C.fg3, marginTop: 8, letterSpacing: '0.04em' }}>NEXT · TEMPO V - UNLOCKS 30/30 LIBRARY</div>
        </div>

        {/* Badges */}
        <div>
          <div style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 11, color: C.fg3, textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 10 }}>Recent badges</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 10 }}>
            {[
              { i: 'flame', l: 'On fire · 7 day' },
              { i: 'zap', l: 'New 5min PB' },
              { i: 'mountain', l: 'Climber I' },
            ].map((b,k) => (
              <div key={k} style={{ background: C.bg1, border: `1px solid ${C.line2}`, borderRadius: 12, padding: '14px 8px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
                <div style={{ width: 44, height: 44, borderRadius: '50%', background: `linear-gradient(135deg, ${C.brand}33, ${C.azure}33)`, color: C.brand, display: 'flex', alignItems: 'center', justifyContent: 'center', border: `1px solid ${C.line2}` }}>
                  <i data-lucide={b.i} style={{width:20,height:20}}></i>
                </div>
                <div style={{ fontFamily: FF.body, fontWeight: 500, fontSize: 11, color: C.fg2, textAlign: 'center' }}>{b.l}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
      <TabBar active="progress" onChange={onTab} />
    </div>
  );
}

Object.assign(window, { RideScreen, SummaryScreen, ProgressScreen });
