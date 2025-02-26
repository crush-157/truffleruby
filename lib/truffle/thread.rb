# truffleruby_primitives: true
# Truffle: Last version of lib/thread.rb in MRI @ r42801 (324df61e).

#
#               thread.rb - thread support classes
#                       by Yukihiro Matsumoto <matz@netlab.co.jp>
#
# Copyright (C) 2001  Yukihiro Matsumoto
# Copyright (C) 2000  Network Applied Communication Laboratory, Inc.
# Copyright (C) 2000  Information-technology Promotion Agency, Japan
#

unless defined? Thread
  raise 'Thread not available for this ruby interpreter'
end

unless defined? ThreadError
  class ThreadError < StandardError
  end
end

if $DEBUG
  Thread.abort_on_exception = true
end

# Truffle: ConditionVariable is defined in the core library.
#          Queue and SizedQueue are defined in Java

class Thread
  Queue = ::Queue
  SizedQueue = ::SizedQueue
end
