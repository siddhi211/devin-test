package com.legacyins.insurance.lifecycle;

import com.legacyins.insurance.workflow.WorkflowSubjectHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class LifecycleWorkflowHandlers {
    @Bean
    WorkflowSubjectHandler cancellationHandler(@Lazy LifecycleService lifecycle) {
        return new WorkflowSubjectHandler() {
            public String subjectType() {
                return "CANCELLATION";
            }

            public void onApproved(Long id, String actor, String note) {
                lifecycle.decideCancellation(id, true, actor, note);
            }

            public void onRejected(Long id, String actor, String note) {
                lifecycle.decideCancellation(id, false, actor, note);
            }
        };
    }

    @Bean
    WorkflowSubjectHandler renewalExceptionHandler(@Lazy LifecycleService lifecycle) {
        return new WorkflowSubjectHandler() {
            public String subjectType() {
                return "RENEWAL_EXCEPTION";
            }

            public void onApproved(Long id, String actor, String note) {
                lifecycle.releaseReferral(id, true, actor, note);
            }

            public void onRejected(Long id, String actor, String note) {
                lifecycle.releaseReferral(id, false, actor, note);
            }
        };
    }
}
