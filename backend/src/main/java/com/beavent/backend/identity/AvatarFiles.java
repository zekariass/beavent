package com.beavent.backend.identity;

import java.util.UUID;

/**
 * Checks that a file may be used as a person's avatar (their own processed public image). Implemented by the files
 * module; without an implementation no avatar can be set.
 */
public interface AvatarFiles {

    boolean isUsableAvatar(UUID userId, UUID fileId);
}
