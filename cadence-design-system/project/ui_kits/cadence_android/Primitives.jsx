// Indoor Bike shared UI primitives — dark Android telemetry styling
// All components Babel-globally-exported via window assignment at bottom

const C = {
  bg0: '#0F0C09',
  bg1: '#181410',
  bg2: '#221C16',
  bg3: '#2D251D',
  bg4: '#3A3025',
  line1: 'rgba(255,236,210,0.06)',
  line2: 'rgba(255,236,210,0.10)',
  line3: 'rgba(255,236,210,0.18)',
  fg1: '#F4ECDF',
  fg2: '#C2B5A1',
  fg3: '#8A7E6C',
  fg4: '#4F4838',
  brand: '#F47A1F',
  brandHi: '#FF9847',
  brandLo: '#B8520A',
  azure: '#D54F0A',
  z: ['#6B8AA8','#2E7BFF','#1FB373','#F0C419','#FF8A1F','#EB3B3B','#C53BFF'],
  zBg: ['rgba(107,138,168,0.16)','rgba(46,123,255,0.18)','rgba(31,179,115,0.18)','rgba(240,196,25,0.20)','rgba(255,138,31,0.20)','rgba(235,59,59,0.20)','rgba(197,59,255,0.20)'],
};

const FF = {
  display: '"Bebas Neue", "Inter", system-ui, sans-serif',
  body: '"Inter", system-ui, sans-serif',
  mono: '"JetBrains Mono", ui-monospace, monospace',
};

// ──────────── Indoor Bike dark Android device ────────────
function IBDevice({ children, width = 390, height = 820 }) {
  return (
    <div style={{
      width, height,
      borderRadius: 38, overflow: 'hidden',
      background: '#000',
      border: '8px solid #16191F',
      boxShadow: '0 30px 80px rgba(0,0,0,0.6), 0 0 0 2px rgba(255,255,255,0.04) inset',
      display: 'flex', flexDirection: 'column',
      position: 'relative',
    }}>
      <IBStatusBar />
      <div style={{ flex: 1, overflow: 'auto', background: C.bg0, position: 'relative' }}>
        <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 80, opacity: 0.5, pointerEvents: 'none', backgroundImage: 'url(../../assets/topo-strip.svg)', backgroundSize: '100% 100%', zIndex: 0 }} />
        {children}
      </div>
      <IBNavBar />
    </div>
  );
}

function IBStatusBar() {
  return (
    <div style={{
      height: 36, display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '0 28px', background: C.bg0, color: C.fg1,
      fontFamily: FF.body, fontSize: 14, fontWeight: 600,
      position: 'relative', flex: 'none',
    }}>
      <span>9:41</span>
      <div style={{
        position: 'absolute', left: '50%', top: 8, transform: 'translateX(-50%)',
        width: 22, height: 22, borderRadius: 100, background: '#000',
      }} />
      <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
        <span style={{ fontSize: 11, opacity: 0.85 }}>5G</span>
        <svg width="14" height="14" viewBox="0 0 16 16"><rect x="2" y="3" width="11" height="10" rx="1.5" fill={C.fg1}/><rect x="13" y="6" width="2" height="4" rx="0.5" fill={C.fg1}/></svg>
      </div>
    </div>
  );
}

function IBNavBar() {
  return (
    <div style={{ height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', background: C.bg0, flex: 'none' }}>
      <div style={{ width: 124, height: 4, borderRadius: 2, background: C.fg2, opacity: 0.5 }} />
    </div>
  );
}

// ──────────── App bar / tab bar / button / chip ────────────
function AppBar({ title, leading, trailing, eyebrow }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 12,
      padding: '14px 20px 12px', background: C.bg0, position: 'sticky', top: 0, zIndex: 5,
    }}>
      {leading}
      <div style={{ flex: 1, minWidth: 0 }}>
        {eyebrow && <div style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 11, letterSpacing: '0.12em', textTransform: 'uppercase', color: C.fg3, marginBottom: 2 }}>{eyebrow}</div>}
        <div style={{ fontFamily: FF.display, fontWeight: 600, fontSize: 22, color: C.fg1, letterSpacing: '0.02em', textTransform: 'uppercase' }}>{title}</div>
      </div>
      {trailing}
    </div>
  );
}

function IconBtn({ name, onClick, color = C.fg1, size = 20, active = false }) {
  React.useEffect(() => { if (window.lucide) window.lucide.createIcons(); });
  return (
    <button onClick={onClick} style={{
      width: 40, height: 40, borderRadius: 12,
      background: active ? C.bg2 : 'transparent',
      border: `1px solid ${active ? C.line2 : 'transparent'}`,
      color, display: 'flex', alignItems: 'center', justifyContent: 'center',
      cursor: 'pointer', padding: 0,
    }}>
      <i data-lucide={name} style={{ width: size, height: size }}></i>
    </button>
  );
}

function PrimaryBtn({ children, onClick, glow = true, full = false }) {
  return (
    <button onClick={onClick} style={{
      background: C.brand, color: '#1a0a00',
      fontFamily: FF.body, fontWeight: 700, fontSize: 15,
      padding: '14px 22px', border: 0, borderRadius: 14, cursor: 'pointer',
      boxShadow: glow ? '0 0 0 1px rgba(244,122,31,0.5), 0 0 32px rgba(244,122,31,0.35)' : 'none',
      width: full ? '100%' : undefined, letterSpacing: '0.01em',
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8,
    }}>{children}</button>
  );
}
function SecondaryBtn({ children, onClick, full = false }) {
  return (
    <button onClick={onClick} style={{
      background: C.bg2, color: C.fg1,
      fontFamily: FF.body, fontWeight: 600, fontSize: 15,
      padding: '13px 20px', border: `1px solid ${C.line2}`, borderRadius: 14, cursor: 'pointer',
      width: full ? '100%' : undefined,
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8,
    }}>{children}</button>
  );
}

function ZoneBadge({ z, label }) {
  const i = Math.max(0, Math.min(6, z - 1));
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '4px 10px', borderRadius: 999,
      background: C.zBg[i], color: C.z[i],
      fontFamily: FF.body, fontWeight: 700, fontSize: 10,
      letterSpacing: '0.1em', textTransform: 'uppercase',
    }}>
      <span style={{ width: 6, height: 6, borderRadius: '50%', background: C.z[i] }} />
      Z{z}{label ? ` · ${label}` : ''}
    </span>
  );
}

function MetaPill({ children, color }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '5px 11px', borderRadius: 999,
      background: C.bg1, border: `1px solid ${C.line2}`,
      color: color || C.fg2, fontFamily: FF.mono, fontSize: 11,
      letterSpacing: '0.04em',
    }}>{children}</span>
  );
}

// ──────────── Workout timeline strip (zone bar) ────────────
function ZoneBar({ segments, height = 10, rounded = true, progress = null }) {
  // segments = [{ zone:1..7, weight: number }]
  const total = segments.reduce((s, x) => s + x.weight, 0);
  let cum = 0;
  return (
    <div style={{ position: 'relative', display: 'flex', height, gap: 1, borderRadius: rounded ? 999 : 4, overflow: 'hidden', background: C.bg0 }}>
      {segments.map((s, i) => {
        const seg = (
          <div key={i} style={{
            background: C.z[s.zone - 1],
            flex: s.weight,
            opacity: progress != null && (cum + s.weight) / total <= progress ? 1 : (progress != null ? 0.35 : 1),
          }} />
        );
        cum += s.weight;
        return seg;
      })}
      {progress != null && (
        <div style={{
          position: 'absolute', top: -2, bottom: -2,
          left: `calc(${progress * 100}% - 1px)`, width: 2, background: C.brand,
          boxShadow: `0 0 8px ${C.brand}`,
        }} />
      )}
    </div>
  );
}

// ──────────── Tab bar (bottom nav) ────────────
function TabBar({ active, onChange }) {
  React.useEffect(() => { if (window.lucide) window.lucide.createIcons(); });
  const tabs = [
    { id: 'rides', icon: 'bike', label: 'Ride' },
    { id: 'history', icon: 'clock', label: 'History' },
    { id: 'progress', icon: 'trophy', label: 'Progress' },
    { id: 'settings', icon: 'settings', label: 'Settings' },
  ];
  return (
    <div style={{
      display: 'flex', background: C.bg1, borderTop: `1px solid ${C.line1}`,
      padding: '6px 8px 4px',
    }}>
      {tabs.map(t => (
        <button key={t.id} onClick={() => onChange(t.id)} style={{
          flex: 1, background: 'transparent', border: 0,
          padding: '8px 4px', display: 'flex', flexDirection: 'column',
          alignItems: 'center', gap: 4, cursor: 'pointer',
          color: active === t.id ? C.brand : C.fg3,
        }}>
          <i data-lucide={t.icon} style={{ width: 22, height: 22 }}></i>
          <span style={{ fontFamily: FF.body, fontWeight: 600, fontSize: 10, letterSpacing: '0.04em' }}>{t.label}</span>
        </button>
      ))}
    </div>
  );
}

Object.assign(window, {
  C, FF,
  IBDevice, IBStatusBar, IBNavBar,
  AppBar, IconBtn, PrimaryBtn, SecondaryBtn,
  ZoneBadge, MetaPill, ZoneBar, TabBar,
});
