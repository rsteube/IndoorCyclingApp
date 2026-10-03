#include "indoorbikecore.h"

#include <QDebug>
#include <QJSEngine>
#include <QJsonDocument>
#include <QJsonObject>
#include <QQmlPropertyMap>

#include "keyedlistmodel.h"

// The half of IndoorBikeCore that does not call into libindoorbikecore: keeping state and lists
// from the events the core sends, and matching replies to requests. Separate so the tests, which
// cannot link the library, test this code rather than a copy of it.

namespace {

const QString TopicReply = QStringLiteral("reply");

// A C callback cannot capture, so it needs somewhere to deliver to.
IndoorBikeCore *g_core = nullptr;

} // namespace

void IndoorBikeCore::setInstance(IndoorBikeCore *core)
{
    g_core = core;
}

IndoorBikeCore *IndoorBikeCore::instance()
{
    return g_core;
}

QVariantMap IndoorBikeCore::send(const QVariantMap &command)
{
    return send(QJsonObject::fromVariantMap(command)).toVariantMap();
}

void IndoorBikeCore::request(const QVariantMap &command, const QJSValue &callback)
{
    const QString id = QStringLiteral("r%1").arg(++m_nextRequest);
    QVariantMap withId = command;
    withId.insert(QStringLiteral("requestId"), id);
    if (callback.isCallable())
        m_pending.insert(id, callback);
    const QVariantMap started = send(withId);
    // Refused before it began: there will be no reply event, so answer now.
    if (!started.value(QStringLiteral("ok")).toBool())
        answer(id, started);
}

KeyedListModel *IndoorBikeCore::list(const QString &topic)
{
    KeyedListModel *model = m_lists.value(topic);
    if (!model) {
        model = new KeyedListModel(this);
        model->setItems(m_state->value(topic).toList());
        m_lists.insert(topic, model);
    }
    return model;
}

void IndoorBikeCore::log(const QString &message, const QString &level)
{
    send(QVariantMap{ { QStringLiteral("cmd"), QStringLiteral("log") },
                      { QStringLiteral("message"), message },
                      { QStringLiteral("level"), level } });
}

void IndoorBikeCore::answer(const QString &id, const QVariantMap &reply)
{
    QJSValue callback = m_pending.take(id);
    if (!callback.isCallable())
        return;
    if (!m_engine) {
        qWarning() << "Core: a reply arrived with no engine to call back in";
        return;
    }
    const QJSValue result = callback.call(QJSValueList{ m_engine->toScriptValue(reply) });
    if (result.isError())
        qWarning() << "Core: a request's callback threw:" << result.toString();
}

void IndoorBikeCore::deliver(const QString &json)
{
    const QJsonObject envelope = QJsonDocument::fromJson(json.toUtf8()).object();
    const QString topic = envelope.value(QStringLiteral("topic")).toString();
    if (topic.isEmpty())
        return;
    const QVariant value = envelope.value(QStringLiteral("payload")).toVariant();

    if (topic == TopicReply) {
        const QVariantMap reply = value.toMap();
        answer(reply.value(QStringLiteral("requestId")).toString(), reply);
        return;
    }

    if (!topic.contains(QLatin1Char('.'))) {
        // The list first, so a handler of the state's change finds the model already updated.
        if (KeyedListModel *model = m_lists.value(topic))
            model->setItems(value.toList());
        m_state->insert(topic, value);
    }
    emit eventReceived(topic, value);
}
