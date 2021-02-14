#!/usr/bin/ruby

# A simple echo server

require_relative 'node.rb'

class EchoNode
  def initialize
    @node = Node.new

    @node.on "echo" do |msg|
      @node.reply! msg, msg[:body].merge(type: "echo_ok")
    end
  end

  def main!
    @node.main!
  end
end

EchoNode.new.main!
