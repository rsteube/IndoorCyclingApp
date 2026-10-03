#include "bluez.h"

#include <QDBusMetaType>

namespace Bluez {

const QString Service = QStringLiteral("org.bluez");
const QString ObjectManagerInterface = QStringLiteral("org.freedesktop.DBus.ObjectManager");
const QString PropertiesInterface = QStringLiteral("org.freedesktop.DBus.Properties");
const QString AdapterInterface = QStringLiteral("org.bluez.Adapter1");
const QString DeviceInterface = QStringLiteral("org.bluez.Device1");
const QString GattServiceInterface = QStringLiteral("org.bluez.GattService1");
const QString GattCharacteristicInterface = QStringLiteral("org.bluez.GattCharacteristic1");

const QString ErrorAlreadyExists = QStringLiteral("org.bluez.Error.AlreadyExists");
const QString ErrorAlreadyConnected = QStringLiteral("org.bluez.Error.AlreadyConnected");
const QString ErrorInProgress = QStringLiteral("org.bluez.Error.InProgress");

void registerTypes()
{
    qDBusRegisterMetaType<InterfaceList>();
    qDBusRegisterMetaType<ManagedObjectList>();
}

QString devicePath(const QString &adapterPath, const QString &address)
{
    QString suffix = address.toUpper();
    suffix.replace(QLatin1Char(':'), QLatin1Char('_'));
    return adapterPath + QStringLiteral("/dev_") + suffix;
}

bool sameUuid(const QString &a, const QString &b)
{
    return a.compare(b, Qt::CaseInsensitive) == 0;
}

} // namespace Bluez
