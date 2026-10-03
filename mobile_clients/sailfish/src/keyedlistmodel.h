#ifndef KEYEDLISTMODEL_H
#define KEYEDLISTMODEL_H

#include <QAbstractListModel>
#include <QVariantList>
#include <QVariantMap>

// A list the core sends whole, kept as a model a view can follow without starting over.
//
// The core publishes a list (rides, workouts, routes) as one JSON array every time any of it
// changes. Handed to a ListView as a plain array, each of those resets the view: delegates are
// rebuilt and the scroll jumps to the top. This compares the new array with the old by each item's
// "id" and tells the view only what changed: rows updated in place, inserted, removed. Only a change
// of order falls back to a reset.
//
// Delegates read `item`, the row's whole object, and `key`, its id. A list grouped by month sets
// monthField to the name of a Unix-seconds field, and `month` is then that time's "yyyy-MM" in the
// phone's own time zone, for a view's section.property. Done here because Qt has the phone's time
// zone database.
//
// Taken from the Companion for UNA's Sailfish app (unawatch/mobile/sailfish), where it was written.
class KeyedListModel : public QAbstractListModel
{
    Q_OBJECT
    Q_PROPERTY(int count READ count NOTIFY countChanged)
    // Goes up on every change, so a binding on get() can depend on it and be re-read.
    Q_PROPERTY(int revision READ revision NOTIFY revisionChanged)
    Q_PROPERTY(QString monthField READ monthField WRITE setMonthField NOTIFY monthFieldChanged)

public:
    enum Roles {
        ItemRole = Qt::UserRole + 1,
        KeyRole,
        MonthRole
    };

    explicit KeyedListModel(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    QVariant data(const QModelIndex &index, int role = Qt::DisplayRole) const override;
    QHash<int, QByteArray> roleNames() const override;

    int count() const { return m_items.count(); }
    int revision() const { return m_revision; }

    QString monthField() const { return m_monthField; }
    void setMonthField(const QString &field);

    // The row with this id, or an empty map.
    Q_INVOKABLE QVariantMap get(const QString &key) const;

    void setItems(const QVariantList &items);

signals:
    void countChanged();
    void monthFieldChanged();
    void revisionChanged();

private:
    static QString keyOf(const QVariant &item);

    QVariantList m_items;
    QString m_monthField;
    int m_revision = 0;
};

#endif // KEYEDLISTMODEL_H
