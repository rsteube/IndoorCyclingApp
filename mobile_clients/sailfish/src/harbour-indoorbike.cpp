#include <QDir>
#include <QGuiApplication>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickView>
#include <QScopedPointer>
#include <QStandardPaths>
#include <QUrl>
#include <QtQml>

#include <keepalive/backgroundactivity.h>
#include <sailfishapp.h>

#include "bluetransport.h"
#include "indoorbikecore.h"
#include "keyedlistmodel.h"
#include "ridechart.h"

namespace {

// One of the app's own folders, made if it is not there yet. Sailjail makes them at an installed
// app's first start; a debug build pushed by sailfish-mcp runs outside it and has to make its own.
QString folder(QStandardPaths::StandardLocation location)
{
    const QString path = QStandardPaths::writableLocation(location);
    QDir().mkpath(path);
    return path;
}

} // namespace

int main(int argc, char *argv[])
{
    QScopedPointer<QGuiApplication> app(SailfishApp::application(argc, argv));
    app->setApplicationVersion(QStringLiteral(APP_VERSION));

#ifdef INDOORBIKE_DEV
    const bool debug = true;
#else
    const bool debug = false;
#endif

    const char *uri = "harbour.indoorbike";
    qmlRegisterUncreatableType<KeyedListModel>(uri, 1, 0, "KeyedListModel", QStringLiteral("Asked of core.list()"));
    qmlRegisterType<RideChart>(uri, 1, 0, "RideChart");

    // The shared Kotlin, which holds everything the app knows. First, so the Kotlin runtime is up
    // before anything asks it something.
    IndoorBikeCore::Folders folders;
    folders.data = folder(QStandardPaths::AppDataLocation);
    folders.config = folder(QStandardPaths::AppConfigLocation);
    folders.cache = folder(QStandardPaths::CacheLocation);
    folders.package = SailfishApp::pathTo(QString()).toLocalFile();
    IndoorBikeCore core(folders, QStringLiteral(APP_VERSION), debug);
    // The radio the core's trainer, heart rate and controller managers drive. Declared after the
    // core so it is destroyed first, while the core can still take its last reports.
    BlueTransport bluetooth(&core);

    // A ride keeps the phone awake: with the screen off it would otherwise suspend, and the
    // trainer's link and the ride's clock with it. The ride screen also keeps the display on, but
    // a rider can still blank it, or leave the app.
    BackgroundActivity keepAwake;
    QObject::connect(&core, &IndoorBikeCore::eventReceived, [&](const QString &topic, const QVariant &payload) {
        if (topic != QLatin1String("ride"))
            return;
        const bool riding = payload.toMap().value(QStringLiteral("active")).toBool();
        if (riding && !keepAwake.isRunning())
            keepAwake.run();
        else if (!riding && !keepAwake.isStopped())
            keepAwake.stop();
    });

    // Scanning for the paired devices follows the app being in front, or a ride, as on Android.
    QObject::connect(app.data(), &QGuiApplication::applicationStateChanged, [&](Qt::ApplicationState state) {
        core.send(QVariantMap{ { QStringLiteral("cmd"), QStringLiteral("app.foreground") },
                               { QStringLiteral("active"), state == Qt::ApplicationActive } });
    });

    QScopedPointer<QQuickView> view(SailfishApp::createView());
    core.setEngine(view->engine());
    QQmlContext *context = view->rootContext();
    context->setContextProperty(QStringLiteral("core"), &core);
    context->setContextProperty(QStringLiteral("appVersion"), QStringLiteral(APP_VERSION));
    context->setContextProperty(QStringLiteral("devBuild"), debug);
    // A debug build pushed by sailfish-mcp keeps its QML beside the binary and names the main
    // file as the first argument; installed, it is where SailfishApp looks.
    QUrl mainQml = SailfishApp::pathToMainQml();
    const QStringList arguments = app->arguments();
    if (arguments.size() > 1 && arguments.at(1).endsWith(QStringLiteral(".qml")))
        mainQml = QUrl::fromLocalFile(arguments.at(1));
    view->setSource(mainQml);
    if (!view->rootObject()) {
        qCritical() << "The main QML did not load";
        return 1;
    }
    view->show();

    return app->exec();
}
