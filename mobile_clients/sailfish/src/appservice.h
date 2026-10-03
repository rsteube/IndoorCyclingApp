#ifndef APPSERVICE_H
#define APPSERVICE_H

#include <QObject>
#include <QStringList>
#include <QVariantMap>

class QQuickView;

// The app on the session bus, as freedesktop's org.freedesktop.Application, which is how Sailfish
// (4.5 and later) starts a sandboxed app and hands it something to open: the .desktop file says
// X-DBusActivatable=true, sailjail starts the app through ExecDBus when it is not running, and
// Lipstick calls Activate or Open on app.indoorbike.indoorbike, the name sailjail gives this app.
//
// Open brings three things, as Android's intent filters do:
//  - indoorbike://oauth?code=..., the browser coming back from a FitTrackee sign-in;
//  - a GPX route, from the file manager, the browser's downloads or another app;
//  - a workout file.
// Each is handed to QML as uriOpened, which knows what to do with it.
//
// The same structure as the Companion for UNA's AppService, where it was measured on a device.
class AppService : public QObject
{
    Q_OBJECT
    Q_CLASSINFO("D-Bus Interface", "org.freedesktop.Application")

public:
    static const QString ServiceName;
    static const QString ObjectPath;

    explicit AppService(QQuickView *view, QObject *parent = nullptr);

    // Takes the object and the name on the session bus. False when either is refused, which leaves
    // the app working as it was, without "open with" or the browser's way back.
    bool registerOnBus();

    // When a copy of the app is already running, hands it [uris] (or just brings it forward) and
    // returns true: this one must then exit. Two copies would fight over the trainer's one link,
    // which BlueZ gives to one client only, and over the history database.
    static bool forwardToRunning(const QStringList &uris);

    // What the app was started with that it should open: the arguments that are URLs or files,
    // without a debug build's main QML file.
    static QStringList openableArguments(const QStringList &arguments);

public slots:
    Q_SCRIPTABLE void Activate(const QVariantMap &platformData);
    Q_SCRIPTABLE void Open(const QStringList &uris, const QVariantMap &platformData);
    Q_SCRIPTABLE void ActivateAction(const QString &name, const QVariantList &parameter,
                                     const QVariantMap &platformData);

signals:
    // Something to open: indoorbike://..., or a file as a local path.
    void uriOpened(const QString &uri);

private:
    void raise();

    QQuickView *m_view;
};

#endif // APPSERVICE_H
