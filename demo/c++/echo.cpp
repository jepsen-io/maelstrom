#include "maelstrom.h"

class EchoMessageHandler : public MessageHandler {
public:
  const std::string &name() const final {
    static const std::string name = "echo";
    return name;
  }

  void handle(const Message &request) final {
    constexpr const char *msgType = "echo_ok";

    auto sender = request.getRecipient();
    auto recipient = request.getSender();
    auto msgBody = request.getBody();
    auto msgId = boost::none;
    auto msgInReplyTo = request.getMsgId();

    Message response{sender, recipient, msgType, msgBody, msgId, msgInReplyTo};
    response.send();
  }
};

int main() {
  Node node;
  node.registerMessageHandler(std::make_unique<EchoMessageHandler>());
  node.run();
  return 0;
}