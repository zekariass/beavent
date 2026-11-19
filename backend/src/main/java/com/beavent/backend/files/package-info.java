/**
 * Files and media (plan step 0.9): direct-to-storage uploads with pre-signed URLs, asynchronous malware scan and
 * content check, image variants with metadata stripped, public files on the public bucket/CDN only after
 * processing, and short-lived signed URLs (audited for {@code SENSITIVE}) for everything else (docs/03 §6).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Files")
package com.beavent.backend.files;
