#ifndef SERVERCONTROLLER_H
#define SERVERCONTROLLER_H

#include <QJsonObject>
#include <QObject>
#include "sshconnection.h"
#include "sshremoteprocess.h"
#include "defs.h"

using namespace amnezia;

class ServerController : public QObject
{
    Q_OBJECT
public:
    typedef QList<QPair<QString, QString>> Vars;

    static ErrorCode fromSshConnectionErrorCode(QSsh::SshError error);

    // QSsh exitCode and exitStatus are different things
    static ErrorCode fromSshProcessExitStatus(int exitStatus);

    static QSsh::SshConnectionParameters sshParams(const ServerCredentials &credentials);
    static void disconnectFromHost(const ServerCredentials &credentials);

    static ErrorCode removeAllContainers(const ServerCredentials &credentials);
    static ErrorCode removeContainer(const ServerCredentials &credentials, DockerContainer container);
    static ErrorCode setupContainer(const ServerCredentials &credentials, DockerContainer container, const QJsonObject &config = QJsonObject());

    static ErrorCode checkOpenVpnServer(DockerContainer container, const ServerCredentials &credentials);

    static ErrorCode uploadFileToHost(const ServerCredentials &credentials, const QByteArray &data, const QString &remotePath);

    static ErrorCode uploadTextFileToContainer(DockerContainer container,
        const ServerCredentials &credentials, const QString &file, const QString &path);

    static QString getTextFileFromContainer(DockerContainer container,
        const ServerCredentials &credentials, const QString &path, ErrorCode *errorCode = nullptr);

    static ErrorCode setupServerFirewall(const ServerCredentials &credentials);

    static QString replaceVars(const QString &script, const Vars &vars);

    static ErrorCode runScript(const QSsh::SshConnectionParameters &sshParams, QString script,
        const std::function<void(const QString &, QSharedPointer<QSsh::SshRemoteProcess>)> &cbReadStdOut = nullptr,
        const std::function<void(const QString &, QSharedPointer<QSsh::SshRemoteProcess>)> &cbReadStdErr = nullptr);

    static Vars genVarsForScript(const ServerCredentials &credentials, DockerContainer container = DockerContainer::None, const QJsonObject &config = QJsonObject());

    static QString checkSshConnection(const ServerCredentials &credentials, ErrorCode *errorCode = nullptr);
private:
    static QSsh::SshConnection *connectToHost(const QSsh::SshConnectionParameters &sshParams);

    static ErrorCode installDocker(const ServerCredentials &credentials);

    static ErrorCode setupOpenVpnServer(const ServerCredentials &credentials, const QJsonObject &config = QJsonObject());
    static ErrorCode setupOpenVpnOverCloakServer(const ServerCredentials &credentials, const QJsonObject &config = QJsonObject());
    static ErrorCode setupShadowSocksServer(const ServerCredentials &credentials, const QJsonObject &config = QJsonObject());
};

#endif // SERVERCONTROLLER_H
