---
status: proposed
---

# Separate Job Posting lifecycle from revision moderation

GuardWork will treat a Job Posting and its candidate-facing Job Posting Revisions as separate concepts. This lets the current Published Revision remain visible while a material edit is reviewed, preserves exactly what a Platform Administrator approved or rejected, and prevents a pending edit from silently changing public content. The additional revision lifecycle is preferred over one combined status because moderation history and public availability change independently.

The physical schema remains subject to explicit schema approval.
