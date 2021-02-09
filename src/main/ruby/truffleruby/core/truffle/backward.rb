# frozen_string_literal: true

# Copyright (c) 2018, 2020 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 2.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

module TruffleRuby
  def self.sulong?
    warn 'TruffleRuby.sulong? has been replaced by TruffleRuby.cexts? and will be removed at a future release', uplevel: 1
    cexts?
  end

  def self.graal?
    warn 'TruffleRuby.graal? has been replaced by TruffleRuby.jit? and will be removed at a future release', uplevel: 1
    jit?
  end
end

module Truffle::System
  def self.synchronized(object)
    warn 'Truffle::System.synchronized has been replaced by TruffleRuby.synchronized and will be removed at a future release', uplevel: 1
    Truffle::System.synchronized(object) do
      yield
    end
  end
end
