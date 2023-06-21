#pragma once

#include <boost/json.hpp>
#include <boost/optional.hpp>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

using namespace std;

// Helper function for raising runtime errors.
template <typename... Args> void raiseRuntimeError(Args &&...args) {
  std::stringstream errorStream;
  (errorStream << ... << args);
  auto error = errorStream.str();
  throw std::runtime_error(error);
}

/**
 * Message class that is used to encapsulate 'Maelstrom' messages.
 */
class Message {
public:
  Message(const std::string &strMessage);

  Message(const std::string &sender, const std::string &recipent,
          const std::string &type,
          const boost::json::object &body = boost::json::object(),
          const boost::optional<int> &msgId = boost::none,
          const boost::optional<int> &inReplyTo = boost::none);

  // Returns the sender of the message.
  std::string getSender() const;

  // Returns the recipient of the message.
  std::string getRecipient() const;

  // Returns the type of the message.
  std::string getType() const;

  // Returns the body of the message.
  const boost::json::object &getBody() const;

  // Returns the message-id if present, otherwise returns 'boost::none'.
  boost::optional<int> getMsgId() const;

  // Returns the reply message-id if present, otherwise returns 'boost::none'.
  boost::optional<int> getInReplyTo() const;

  // Sends the message to the 'Maelstrom'.
  void send() const;

private:
  // Field 'src' of the message.
  std::string _sender;

  // Field 'dest' of the message.
  std::string _recipient;

  // Field 'type' of the message.
  std::string _type;

  // Field 'body' of the message.
  boost::json::object _body;

  // Field 'msg_id' of the message if present.
  boost::optional<int> _msgId;

  // Field 'in_reply_to' of the message if present.
  boost::optional<int> _inReplyTo;
};

/**
 * Abstract base class for writing custom message handler.
 */
class MessageHandler {
public:
  virtual ~MessageHandler() = default;

  // Returns the type of message associated with this handler.
  virtual const std::string &name() const = 0;

  // Handles the provided message which is guaranteed to be of the expected
  // type.
  virtual void handle(const Message &message) = 0;
};

using MessageHandlerPtr = std::unique_ptr<MessageHandler>;

/**
 * Node class that interacts with the 'Maelstrom'.
 */
class Node {
public:
  // Starts receiving messages from the 'Maelstrom' and dispatches them to the
  // appropriate message handler. The user should call this method.
  void run();

  // Registers a message handler with the node. The user should call this method
  // before calling 'run()'.
  void registerMessageHandler(MessageHandlerPtr msgHandler);

private:
  // Initializes the node with 'nodeId' and 'peerIds'.
  void _init(const Message &message);

  // Calls the appropriate message handler for the provided message.
  void handleMessage(const Message &message);

  // Maps message type to message handler.
  std::unordered_map<std::string, MessageHandlerPtr> _messageHandlers;

  // Node ID of this node.
  boost::optional<std::string> _nodeId;

  // Node IDs of the peers of this node.
  std::vector<std::string> _peerIds;
};
