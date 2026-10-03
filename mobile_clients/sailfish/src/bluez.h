#ifndef BLUEZ_H
#define BLUEZ_H

// The slice of BlueZ's D-Bus API this app uses.
//
// Qt Bluetooth is not in the Sailfish target, and the Bluetooth sailjail permission grants
// exactly one thing: talking to org.bluez on the system bus. So the app speaks BlueZ's own
// object API through QtDBus.
//
// Taken from the Companion for UNA's Sailfish app (unawatch/mobile/sailfish), where it was written.

#include <QDBusObjectPath>
#include <QMap>
#include <QMetaType>
#include <QString>
#include <QVariantMap>

// org.freedesktop.DBus.ObjectManager's shapes: interface name to its properties, and object
// path to its interfaces.
typedef QMap<QString, QVariantMap> InterfaceList;
typedef QMap<QDBusObjectPath, InterfaceList> ManagedObjectList;

Q_DECLARE_METATYPE(InterfaceList)
Q_DECLARE_METATYPE(ManagedObjectList)

namespace Bluez {

extern const QString Service;
extern const QString ObjectManagerInterface;
extern const QString PropertiesInterface;
extern const QString AdapterInterface;
extern const QString DeviceInterface;
extern const QString GattServiceInterface;
extern const QString GattCharacteristicInterface;

// Error names BlueZ answers with that the app treats differently from a plain failure.
extern const QString ErrorAlreadyExists;
extern const QString ErrorAlreadyConnected;
extern const QString ErrorInProgress;

// Registers the ObjectManager types with QtDBus. Call once, before any BlueZ call.
void registerTypes();

// BlueZ names a device after its address: AA:BB:CC:DD:EE:FF under /org/bluez/hci0 is
// /org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF.
QString devicePath(const QString &adapterPath, const QString &address);

// True when the two UUIDs are the same. BlueZ reports full lower-case 128-bit UUIDs, but a
// raw string compare against a differently cased constant silently never matches.
bool sameUuid(const QString &a, const QString &b);

} // namespace Bluez

#endif // BLUEZ_H
