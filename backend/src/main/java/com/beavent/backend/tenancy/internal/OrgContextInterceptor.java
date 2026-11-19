package com.beavent.backend.tenancy.internal;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RequestContext;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * For every {@code /v1/orgs/{orgId}/...} request: path organization → active membership → bind {@link OrgAccess} and
 * narrow the database context to that organization (docs/03 §2.6). Non-members get {@code 404}, so organization ids
 * cannot be probed; suspended organizations are read-only.
 */
class OrgContextInterceptor implements HandlerInterceptor {

    private static final String SCOPE = OrgContextInterceptor.class.getName() + ".scope";
    private static final String BINDING = OrgContextInterceptor.class.getName() + ".binding";

    private final Memberships memberships;

    OrgContextInterceptor(Memberships memberships) {
        this.memberships = memberships;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        @SuppressWarnings("unchecked")
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String rawOrgId = variables == null ? null : variables.get("orgId");
        if (rawOrgId == null) {
            return true;
        }
        RequestContext caller = RequestContext.current();
        if (caller == null || caller.userId() == null) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED);
        }
        UUID orgId;
        try {
            orgId = UUID.fromString(rawOrgId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        Memberships.Entry entry = memberships.find(orgId, caller.userId());
        if (entry == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        if ("SUSPENDED".equals(entry.orgStatus()) && !"GET".equals(request.getMethod())) {
            throw new ApiException(ProblemCode.FORBIDDEN, "This organization is suspended. Contact support.");
        }
        request.setAttribute(
                SCOPE, RequestContext.forOrganization(caller.userId(), orgId).open());
        request.setAttribute(BINDING, OrgAccess.bind(entry.access()));
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        if (request.getAttribute(BINDING) instanceof OrgAccess.Binding binding) {
            binding.close();
        }
        if (request.getAttribute(SCOPE) instanceof RequestContext.Scope scope) {
            scope.close();
        }
    }
}
