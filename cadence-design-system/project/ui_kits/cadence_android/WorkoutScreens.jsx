// Indoor Bike - screen components

function WorkoutListScreen({ onPick, onTab }) {
  React.useEffect(() => { if (window.lucide) window.lucide.createIcons(); });

  const workouts = [
    { id: 'sweet', title: 'Sweet Spot 3×12', tag: 'Threshold', dur: '52 min', tss: 65, if: 0.91,
      segs: [{zone:2,weight:2},{zone:3,weight:1},{zone:4,weight:5},{zone:3,weight:1},{zone:4,weight:5},{zone:3,weight:1},{zone:4,weight:5},{zone:2,weight:1}] },
    { id: 'vo2', title: '30/30s · 8 sets', tag: 'VO2 Max', dur: '38 min', tss: 58, if: 0.94,
      segs: [{zone:2,weight:3},{zone:5,weight:1},{zone:1,weight:1},{zone:5,weight:1},{zone:1,weight:1},{zone:5,weight:1},{zone:1,weight:1},{zone:5,weight:1},{zone:1,weight:1},{zone:2,weight:3}] },
    { id: 'end', title: 'Endurance 90', tag: 'Endurance', dur: '90 min', tss: 78, if: 0.72,
      segs: [{zone:2,weight:1},{zone:2,weight:8},{zone:3,weight:2},{zone:2,weight:5},{zone:2,weight:1}] },
    { id: 'fr', title: 'Recovery Spin', tag: 'Recovery', dur: '30 min', tss: 18, if: 0.55,
      segs: [{zone:1,weight:1},{zone:2,weight:8},{zone:1,weight:2}] },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <AppBar
        eyebrow="Tuesday · 6:14 AM"
        title="Pick a workout"
        leading={<img src="../../assets/logo-mark.svg" style={{width:32,height:32}} />}
        trailing={<IconBtn name="search" />}
      />

      {/* Trainer status strip */}
      <div style={{ margin: '0 20px 16px', padding: '12px 14px', background: C.bg1, border: `1px solid ${C.line2}`, borderRadius: 12, display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{ width: 36, height: 36, borderRadius: 10, background: 'rgba(244,122,31,0.10)', color: C.brand, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <i data-lucide="bluetooth-connected" style={{width:18,height:18}}></i>
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 14, color: C.fg1 }}>Wahoo Kickr Core</div>
          <div style={{ fontFamily: FF.mono, fontSize: 11, color: C.fg3, letterSpacing: '0.04em' }}>Connected · FTP 248 W</div>
        </div>
        <div style={{ width: 8, height: 8, borderRadius: '50%', background: C.z[2], boxShadow: `0 0 10px ${C.z[2]}` }} />
      </div>

      {/* Filter chips */}
      <div style={{ display: 'flex', gap: 6, padding: '0 20px 12px', overflowX: 'auto' }}>
        {['All', 'Endurance', 'Threshold', 'VO2', 'Recovery'].map((t,i) => (
          <span key={t} style={{
            padding: '6px 12px', borderRadius: 999,
            background: i === 0 ? C.brand : C.bg2,
            color: i === 0 ? '#1a0a00' : C.fg2,
            border: `1px solid ${i === 0 ? 'transparent' : C.line2}`,
            fontFamily: FF.body, fontWeight: 600, fontSize: 12, whiteSpace: 'nowrap',
          }}>{t}</span>
        ))}
      </div>

      {/* List */}
      <div style={{ padding: '0 20px 20px', display: 'flex', flexDirection: 'column', gap: 10, flex: 1 }}>
        {workouts.map(w => (
          <button key={w.id} onClick={() => onPick(w)} style={{
            background: C.bg1, border: `1px solid ${C.line2}`, borderRadius: 14,
            padding: 16, textAlign: 'left', cursor: 'pointer', color: C.fg1,
            display: 'flex', flexDirection: 'column', gap: 12,
          }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 11, color: C.fg3, textTransform: 'uppercase', letterSpacing: '0.1em' }}>{w.tag}</div>
                <div style={{ fontFamily: FF.display, fontWeight: 600, fontSize: 18, color: C.fg1, marginTop: 2, letterSpacing: '0.02em', textTransform: 'uppercase' }}>{w.title}</div>
              </div>
              <i data-lucide="chevron-right" style={{ width: 18, height: 18, color: C.fg3 }}></i>
            </div>
            <ZoneBar segments={w.segs} height={8} />
            <div style={{ display: 'flex', gap: 6 }}>
              <MetaPill>{w.dur.toUpperCase()}</MetaPill>
              <MetaPill>TSS {w.tss}</MetaPill>
              <MetaPill>IF {w.if}</MetaPill>
            </div>
          </button>
        ))}
      </div>

      <TabBar active="rides" onChange={onTab} />
    </div>
  );
}

// ──────────── Workout detail / preview ────────────
function WorkoutDetailScreen({ workout, onBack, onStart }) {
  React.useEffect(() => { if (window.lucide) window.lucide.createIcons(); });
  const segs = workout.segs;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <AppBar
        title={workout.title}
        eyebrow={workout.tag}
        leading={<IconBtn name="arrow-left" onClick={onBack} />}
        trailing={<IconBtn name="more-vertical" />}
      />
      <div style={{ flex: 1, padding: '0 20px 20px', display: 'flex', flexDirection: 'column', gap: 18 }}>
        {/* Big zone chart */}
        <div style={{ background: C.bg1, border: `1px solid ${C.line2}`, borderRadius: 14, padding: 18 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 14 }}>
            <div>
              <div style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 11, color: C.fg3, textTransform: 'uppercase', letterSpacing: '0.1em' }}>Workout profile</div>
              <div style={{ fontFamily: FF.display, fontWeight: 600, fontSize: 22, color: C.fg1, marginTop: 4 }}>{workout.dur}</div>
            </div>
            <div style={{ textAlign: 'right' }}>
              <div style={{ fontFamily: FF.mono, fontSize: 11, color: C.fg3, letterSpacing: '0.04em' }}>NORMALIZED</div>
              <div style={{ fontFamily: FF.display, fontWeight: 700, fontSize: 22, color: C.brand }}>{Math.round(workout.if * 248)} W</div>
            </div>
          </div>
          {/* Stepped zone profile */}
          <div style={{ display: 'flex', alignItems: 'flex-end', height: 110, gap: 1 }}>
            {segs.map((s, i) => {
              const h = [22, 38, 56, 78, 92, 100, 100][s.zone - 1];
              return <div key={i} style={{ background: C.z[s.zone-1], flex: s.weight, height: `${h}%`, borderRadius: '3px 3px 0 0' }} />;
            })}
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 6, fontFamily: FF.mono, fontSize: 10, color: C.fg3 }}>
            <span>0:00</span><span>{workout.dur}</span>
          </div>
        </div>

        {/* Stats grid */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8 }}>
          {[
            { l: 'TSS', v: workout.tss },
            { l: 'IF', v: workout.if },
            { l: 'kJ', v: Math.round(workout.tss * 4.1) },
          ].map(s => (
            <div key={s.l} style={{ background: C.bg1, border: `1px solid ${C.line2}`, borderRadius: 12, padding: '12px 14px' }}>
              <div style={{ fontFamily: FF.mono, fontSize: 10, color: C.fg3, letterSpacing: '0.08em' }}>{s.l.toUpperCase()}</div>
              <div style={{ fontFamily: FF.display, fontWeight: 700, fontSize: 22, color: C.fg1, marginTop: 2 }}>{s.v}</div>
            </div>
          ))}
        </div>

        {/* Step list preview */}
        <div>
          <div style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 11, color: C.fg3, textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 10 }}>Steps · {segs.length}</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {segs.slice(0, 5).map((s, i) => (
              <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 12px', background: C.bg1, border: `1px solid ${C.line1}`, borderRadius: 10 }}>
                <div style={{ width: 4, height: 28, borderRadius: 2, background: C.z[s.zone-1] }} />
                <ZoneBadge z={s.zone} />
                <div style={{ flex: 1, fontFamily: FF.mono, fontSize: 12, color: C.fg2, textAlign: 'right' }}>
                  {Math.round(s.weight * 60)}s · {Math.round([0.5,0.65,0.83,0.98,1.13,1.35,1.6][s.zone-1] * 248)} W
                </div>
              </div>
            ))}
            {segs.length > 5 && <div style={{ textAlign: 'center', fontFamily: FF.body, fontSize: 12, color: C.fg3, padding: 6 }}>+ {segs.length - 5} more</div>}
          </div>
        </div>
      </div>

      <div style={{ padding: '12px 20px 16px', background: C.bg0, borderTop: `1px solid ${C.line1}` }}>
        <PrimaryBtn full onClick={onStart}>
          <i data-lucide="play" style={{width:18,height:18}}></i>
          Start workout
        </PrimaryBtn>
      </div>
    </div>
  );
}

Object.assign(window, { WorkoutListScreen, WorkoutDetailScreen });
