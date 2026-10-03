#ifndef NOTIFYPIPE_H
#define NOTIFYPIPE_H

#include <QByteArray>
#include <QDBusConnection>
#include <QObject>
#include <QString>

class QDBusPendingCallWatcher;
class QSocketNotifier;

// One GATT characteristic's notifications as a socket, through BlueZ's AcquireNotify, instead of
// as D-Bus traffic.
//
// Why: D-Bus loses notifications. BlueZ reports one by changing the characteristic's Value
// property, and its D-Bus layer emits property changes from an idle callback, skipping a property
// that is already waiting: two notifications close together go out as one signal carrying the
// second. A trainer's Indoor Bike Data and Cycling Power arrive four times a second, often on the
// same connection event. A socket delivers each notification as its own datagram, in order.
//
// AcquireNotify takes only characteristics that notify, not ones that indicate (the FTMS control
// point), and an older BlueZ may refuse it. The owner then falls back to StartNotify, which works,
// with D-Bus's merging.
//
// The notify half of the Companion for UNA's GattPipe (unawatch/mobile/sailfish/src/gattpipe.*);
// the trainer's writes are few and need a response, so they stay on D-Bus.
class NotifyPipe : public QObject
{
    Q_OBJECT

public:
    NotifyPipe(const QDBusConnection &bus, const QString &characteristicPath, QObject *parent = nullptr);
    ~NotifyPipe() override;

    void acquire();

signals:
    // Notifications flow; frames arrive on notification() from now on.
    void notifying(int mtu);
    // BlueZ would not hand over the socket; the owner should use StartNotify instead.
    void refused(const QString &reason);
    void notification(const QByteArray &frame);
    // The socket closed under it, which is what a dropped link looks like from here.
    void closed();

private slots:
    void onAcquired(QDBusPendingCallWatcher *call);
    void onReadable();

private:
    void closeFd();

    QDBusConnection m_bus;
    QString m_path;
    int m_fd = -1;
    QSocketNotifier *m_notifier = nullptr;
};

#endif // NOTIFYPIPE_H
