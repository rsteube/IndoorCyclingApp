#include "notifypipe.h"

#include <QDBusMessage>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QDBusUnixFileDescriptor>
#include <QDebug>
#include <QSocketNotifier>

#include <cerrno>
#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>

#include "bluez.h"

namespace {

// Larger than any ATT MTU, so a datagram is never cut short.
const int MaxFrame = 1024;

} // namespace

NotifyPipe::NotifyPipe(const QDBusConnection &bus, const QString &characteristicPath, QObject *parent)
    : QObject(parent)
    , m_bus(bus)
    , m_path(characteristicPath)
{
}

NotifyPipe::~NotifyPipe()
{
    closeFd();
}

void NotifyPipe::acquire()
{
    QDBusMessage notify = QDBusMessage::createMethodCall(Bluez::Service, m_path, Bluez::GattCharacteristicInterface,
                                                         QStringLiteral("AcquireNotify"));
    notify << QVariantMap();
    connect(new QDBusPendingCallWatcher(m_bus.asyncCall(notify), this), &QDBusPendingCallWatcher::finished, this,
            &NotifyPipe::onAcquired);
}

void NotifyPipe::onAcquired(QDBusPendingCallWatcher *call)
{
    call->deleteLater();
    const QDBusPendingReply<QDBusUnixFileDescriptor, quint16> reply = *call;
    if (reply.isError()) {
        emit refused(reply.error().name() + QLatin1String(": ") + reply.error().message());
        return;
    }
    // The fd QtDBus hands over is closed with the reply, so the pipe keeps its own copy.
    const QDBusUnixFileDescriptor descriptor = reply.argumentAt<0>();
    m_fd = descriptor.isValid() ? ::dup(descriptor.fileDescriptor()) : -1;
    if (m_fd < 0) {
        emit refused(QStringLiteral("no usable file descriptor"));
        return;
    }
    ::fcntl(m_fd, F_SETFL, ::fcntl(m_fd, F_GETFL) | O_NONBLOCK);
    ::fcntl(m_fd, F_SETFD, FD_CLOEXEC);
    m_notifier = new QSocketNotifier(m_fd, QSocketNotifier::Read, this);
    connect(m_notifier, &QSocketNotifier::activated, this, &NotifyPipe::onReadable);
    emit notifying(reply.argumentAt<1>());
}

void NotifyPipe::onReadable()
{
    char buffer[MaxFrame];
    // Every datagram waiting, so a burst is handed on in one pass of the event loop.
    for (;;) {
        const ssize_t n = ::recv(m_fd, buffer, sizeof(buffer), 0);
        if (n > 0) {
            emit notification(QByteArray(buffer, static_cast<int>(n)));
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
            return;
        if (n < 0 && errno == EINTR)
            continue;
        // Zero or a hard error: BlueZ closed it, which it does when the link goes.
        closeFd();
        emit closed();
        return;
    }
}

void NotifyPipe::closeFd()
{
    delete m_notifier;
    m_notifier = nullptr;
    if (m_fd >= 0)
        ::close(m_fd);
    m_fd = -1;
}
