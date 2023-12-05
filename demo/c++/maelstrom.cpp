#include "maelstrom.h"
#include <future>
#include <iostream>

//
//  Message
//
Message::Message(const std::string &strMessage) {
  auto jsonValue = boost::json::parse(strMessage);
  if (!jsonValue.is_object()) {
    raiseRuntimeError("Invalid message: ", strMessage);
  }

  auto jsonObject = jsonValue.as_object();

  _sender = jsonObject.at("src").as_string().c_str();
  _recipient = jsonObject.at("dest").as_string().c_str();
  _body = jsonObject.at("body").as_object();
  _type = _body.at("type").as_string().c_str();

  if (_body.contains("msg_id")) {
    _msgId = _body.at("msg_id").as_int64();
  }

  if (_body.contains("in_reply_to")) {
    _inReplyTo = _body.at("in_reply_to").as_int64();
  }
}

Message::Message(const std::string &sender, const std::string &recipent,
                 const std::string &type, const boost::json::object &body,
                 const boost::optional<int> &msgId,
                 const boost::optional<int> &inReplyTo)
    : _sender(sender), _recipient(recipent), _type(type), _body(body),
      _msgId(msgId), _inReplyTo(inReplyTo) {
  _body["type"] = _type;

  if (msgId && inReplyTo) {
    raiseRuntimeError("'msg_Id' and 'in_reply_to' cannot be set together");
  }

  if (_msgId) {
    _body["msg_id"] = *_msgId;
    if (_body.contains("in_reply_to")) {
      _body.erase("in_reply_to");
    }
  }

  if (_inReplyTo) {
    _body["in_reply_to"] = *_inReplyTo;
    if (_body.contains("msg_id")) {
      _body.erase("msg_id");
    }
  }
}

std::string Message::getSender() const { return _sender; }

std::string Message::getRecipient() const { return _recipient; }

std::string Message::getType() const { return _type; }

const boost::json::object &Message::getBody() const { return _body; }

boost::optional<int> Message::getMsgId() const { return _msgId; }

boost::optional<int> Message::getInReplyTo() const { return _inReplyTo; }

void Message::send() const {
  boost::json::object jsonMessage;
  jsonMessage["src"] = _sender;
  jsonMessage["dest"] = _recipient;
  jsonMessage["body"] = _body;
  std::cout << jsonMessage << std::endl;
}

//
//  Node
//
void Node::run() {
  while (true) {
    std::string inputLine;
    std::getline(std::cin, inputLine);
    if (inputLine.empty()) {
      break;
    }

    Message request{inputLine};
    handleMessage(request);
  }
}

void Node::registerMessageHandler(MessageHandlerPtr msgHandler) {
  _messageHandlers[msgHandler->name()] = std::move(msgHandler);
}

void Node::handleMessage(const Message &request) {
  auto msgType = request.getType();

  if (msgType == "init") {
    _init(request);
    return;
  }

  if (auto handlersIter = _messageHandlers.find(msgType);
      handlersIter != _messageHandlers.end()) {
    std::async(std::launch::async,
               [&]() { handlersIter->second->handle(request); });
  } else {
    raiseRuntimeError("No handler found for message type", msgType);
  }
}

void Node::_init(const Message &request) {
  if (_nodeId) {
    raiseRuntimeError("Node: ", *_nodeId, " already initialized");
  }

  auto requestBody = request.getBody();
  _nodeId = requestBody.at("node_id").as_string().c_str();

  _peerIds = [&]() {
    std::vector<std::string> peers;
    for (const auto &item : requestBody.at("node_ids").as_array()) {
      peers.push_back(item.as_string().c_str());
    }
    return peers;
  }();

  constexpr const char *msgType = "init_ok";
  auto sender = request.getRecipient();
  auto recipient = request.getSender();
  auto responseBody = boost::json::object();
  auto msgId = boost::none;
  auto msgInReplyTo = request.getMsgId();

  Message response{sender,       recipient, msgType,
                   responseBody, msgId,     msgInReplyTo};
  response.send();
}
