Name:       harbour-indoorbike

Summary:    Indoor cycling with a smart trainer
Version:    0.3.0
Release:    1
License:    GPLv3
URL:        https://indoorbike.app
Source0:    %{name}-%{version}.tar.bz2
Requires:   sailfishsilica-qt5 >= 0.10.9
BuildRequires:  pkgconfig(sailfishapp) >= 1.0.2
BuildRequires:  pkgconfig(Qt5Core)
BuildRequires:  pkgconfig(Qt5DBus)
BuildRequires:  pkgconfig(Qt5Qml)
BuildRequires:  pkgconfig(Qt5Quick)
BuildRequires:  pkgconfig(keepalive)
# Only tests/tests.pro uses Qt Test. It is here because this spec is what sfdk reads to set up the
# build target, and tests/run.sh pulls it from here.
BuildRequires:  pkgconfig(Qt5Test)
BuildRequires:  desktop-file-utils

# libindoorbikecore.so is the prebuilt shared Kotlin, in the app's own lib directory where its
# rpath finds it. It provides nothing to the rest of the system, and the app's link to it is not an
# outside dependency, so rpm must advertise neither.
%define __provides_exclude_from ^%{_datadir}/%{name}/lib/.*$
%define __requires_exclude ^libindoorbikecore\\.so.*$

%description
Ride structured workouts, routes and companion games on a smart trainer,
with heart rate, and keep the rides: history, FIT export, Intervals.icu.


%prep
%setup -q -n %{name}-%{version}

%build

%qmake5 VERSION=%{version}

%make_build


%install
%qmake5_install

# Nothing else strips the binary: the qmake5 macro passes QMAKE_STRIP=:, and the brp-strip the
# install step names is not at that path in the build target. Comments here must not name a macro
# with its percent sign: rpm expands macros inside comments too.
%{__strip} %{buildroot}%{_bindir}/%{name}
# Only unneeded symbols from the core: the app reaches it through the symbol table Kotlin/Native
# exports, which a plain strip would remove.
%{__strip} --strip-unneeded %{buildroot}%{_datadir}/%{name}/lib/libindoorbikecore.so

desktop-file-install --delete-original       \
  --dir %{buildroot}%{_datadir}/applications             \
   %{buildroot}%{_datadir}/applications/*.desktop

%files
%defattr(-,root,root,-)
%{_bindir}/%{name}
%{_datadir}/%{name}
%{_datadir}/applications/%{name}.desktop
%{_datadir}/icons/hicolor/*/apps/%{name}.png
