package io.dockstore.webservice.authorization;

import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.jdbi.TokenDAO;

public final class AuthorizationFactory {

    private static AuthorizationInterface authorizationInterface;

    private AuthorizationFactory() {
    }

    public static AuthorizationInterface getAuthorizer(TokenDAO tokenDAO, DockstoreWebserviceConfiguration configuration) {
        synchronized (AuthorizationFactory.class) {
            if (authorizationInterface == null) {
//                authorizationInterface = new InMemoryAuthorizer();
                authorizationInterface = new SamAuthorizer(tokenDAO, configuration);
            }
            return authorizationInterface;
        }
    }

}
