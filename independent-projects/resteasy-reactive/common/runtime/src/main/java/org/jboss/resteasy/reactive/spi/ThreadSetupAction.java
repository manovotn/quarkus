package org.jboss.resteasy.reactive.spi;

public interface ThreadSetupAction {

    ThreadState activateInitial();

    ThreadState currentState();

    boolean isRequestContextActive();

    interface ThreadState {
        void close();

        void activate();

        void deactivate();
    }

    ThreadSetupAction NOOP = new ThreadSetupAction() {
        @Override
        public ThreadState activateInitial() {
            return new ThreadState() {
                @Override
                public void close() {

                }

                @Override
                public void activate() {

                }

                @Override
                public void deactivate() {

                }
            };
        }

        @Override
        public ThreadState currentState() {
            return activateInitial();
        }

        @Override
        public boolean isRequestContextActive() {
            return false; // TODO false IMO makes sense as default but I didn't dig deeper for this NOOP obj.
        }
    };
}
