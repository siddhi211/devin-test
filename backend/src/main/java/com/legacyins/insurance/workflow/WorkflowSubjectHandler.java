package com.legacyins.insurance.workflow;

/** Callback implemented by domain modules that route work through approval workflows. */
public interface WorkflowSubjectHandler {
    String subjectType();

    void onApproved(Long subjectId, String actor, String note);

    void onRejected(Long subjectId, String actor, String note);
}
