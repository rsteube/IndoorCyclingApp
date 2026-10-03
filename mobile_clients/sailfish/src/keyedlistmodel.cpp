#include "keyedlistmodel.h"

#include <QDateTime>
#include <QSet>
#include <QStringList>

KeyedListModel::KeyedListModel(QObject *parent)
    : QAbstractListModel(parent)
{
}

int KeyedListModel::rowCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : m_items.count();
}

QVariant KeyedListModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() >= m_items.count())
        return QVariant();
    switch (role) {
    case ItemRole:
        return m_items.at(index.row());
    case KeyRole:
        return keyOf(m_items.at(index.row()));
    case MonthRole: {
        if (m_monthField.isEmpty())
            return QString();
        const qint64 seconds = m_items.at(index.row()).toMap().value(m_monthField).toLongLong();
        return QDateTime::fromMSecsSinceEpoch(seconds * 1000).toString(QStringLiteral("yyyy-MM"));
    }
    case WeekRole: {
        if (m_weekField.isEmpty())
            return QString();
        const qint64 seconds = m_items.at(index.row()).toMap().value(m_weekField).toLongLong();
        const QDate day = QDateTime::fromMSecsSinceEpoch(seconds * 1000).date();
        return day.addDays(1 - day.dayOfWeek()).toString(Qt::ISODate);
    }
    default:
        return QVariant();
    }
}

QHash<int, QByteArray> KeyedListModel::roleNames() const
{
    return { { ItemRole, "item" }, { KeyRole, "key" }, { MonthRole, "month" }, { WeekRole, "week" } };
}

void KeyedListModel::setMonthField(const QString &field)
{
    if (field == m_monthField)
        return;
    m_monthField = field;
    emit monthFieldChanged();
    if (!m_items.isEmpty())
        emit dataChanged(index(0), index(m_items.count() - 1), { MonthRole });
}

void KeyedListModel::setWeekField(const QString &field)
{
    if (field == m_weekField)
        return;
    m_weekField = field;
    emit weekFieldChanged();
    if (!m_items.isEmpty())
        emit dataChanged(index(0), index(m_items.count() - 1), { WeekRole });
}

QVariantMap KeyedListModel::get(const QString &key) const
{
    for (const QVariant &item : m_items) {
        if (keyOf(item) == key)
            return item.toMap();
    }
    return QVariantMap();
}

QString KeyedListModel::keyOf(const QVariant &item)
{
    return item.toMap().value(QStringLiteral("id")).toString();
}

void KeyedListModel::setItems(const QVariantList &items)
{
    if (items == m_items)
        return;
    const int before = m_items.count();
    // Whatever happens below, the list changed.
    struct Bump {
        KeyedListModel *model;
        ~Bump() { ++model->m_revision; emit model->revisionChanged(); }
    } bump{ this };

    QSet<QString> incoming;
    QStringList newKeys;
    for (const QVariant &item : items) {
        newKeys.append(keyOf(item));
        incoming.insert(newKeys.last());
    }

    // Rows that went, from the bottom up so the indices above stay right.
    for (int row = m_items.count() - 1; row >= 0; --row) {
        if (!incoming.contains(keyOf(m_items.at(row)))) {
            beginRemoveRows(QModelIndex(), row, row);
            m_items.removeAt(row);
            endRemoveRows();
        }
    }

    // What is left keeps its order in both lists, unless something moved. Then the cheap answer
    // is the honest one.
    QStringList kept;
    for (const QVariant &item : m_items)
        kept.append(keyOf(item));
    QStringList keptInNew;
    for (const QString &key : newKeys) {
        if (kept.contains(key))
            keptInNew.append(key);
    }
    if (kept != keptInNew) {
        beginResetModel();
        m_items = items;
        endResetModel();
        if (m_items.count() != before)
            emit countChanged();
        return;
    }

    // Walk the new list: a key not present yet is inserted where it belongs, one present is
    // updated if its contents changed.
    for (int row = 0; row < items.count(); ++row) {
        const QString key = newKeys.at(row);
        if (row < m_items.count() && keyOf(m_items.at(row)) == key) {
            if (m_items.at(row) != items.at(row)) {
                m_items[row] = items.at(row);
                const QModelIndex changed = index(row);
                emit dataChanged(changed, changed, { ItemRole, MonthRole, WeekRole });
            }
        } else {
            beginInsertRows(QModelIndex(), row, row);
            m_items.insert(row, items.at(row));
            endInsertRows();
        }
    }

    if (m_items.count() != before)
        emit countChanged();
}
