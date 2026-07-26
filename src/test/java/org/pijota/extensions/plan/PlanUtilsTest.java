package org.pijota.extensions.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanUtilsTest {

    @Test
    void safeReadOnlyCommands() {
        assertTrue(PlanUtils.isSafeCommand("ls"));
        assertTrue(PlanUtils.isSafeCommand("cat file.txt"));
        assertTrue(PlanUtils.isSafeCommand("head -n 10 file.txt"));
        assertTrue(PlanUtils.isSafeCommand("grep pattern file.txt"));
        assertTrue(PlanUtils.isSafeCommand("find . -name '*.java'"));
        assertTrue(PlanUtils.isSafeCommand("pwd"));
        assertTrue(PlanUtils.isSafeCommand("echo hello"));
        assertTrue(PlanUtils.isSafeCommand("wc -l file.txt"));
        assertTrue(PlanUtils.isSafeCommand("sort file.txt"));
        assertTrue(PlanUtils.isSafeCommand("uniq file.txt"));
        assertTrue(PlanUtils.isSafeCommand("diff a.txt b.txt"));
    }

    @Test
    void safeDevCommands() {
        assertTrue(PlanUtils.isSafeCommand("git status"));
        assertTrue(PlanUtils.isSafeCommand("git log"));
        assertTrue(PlanUtils.isSafeCommand("git diff"));
        assertTrue(PlanUtils.isSafeCommand("git branch"));
        assertTrue(PlanUtils.isSafeCommand("npm list"));
        assertTrue(PlanUtils.isSafeCommand("npm outdated"));
        assertTrue(PlanUtils.isSafeCommand("node --version"));
        assertTrue(PlanUtils.isSafeCommand("python --version"));
    }

    @Test
    void destructiveCommandsAreUnsafe() {
        assertFalse(PlanUtils.isSafeCommand("rm file.txt"));
        assertFalse(PlanUtils.isSafeCommand("rm -rf dir"));
        assertFalse(PlanUtils.isSafeCommand("mv a.txt b.txt"));
        assertFalse(PlanUtils.isSafeCommand("cp a.txt b.txt"));
        assertFalse(PlanUtils.isSafeCommand("mkdir newdir"));
        assertFalse(PlanUtils.isSafeCommand("touch file.txt"));
        assertFalse(PlanUtils.isSafeCommand("chmod 755 script.sh"));
        assertFalse(PlanUtils.isSafeCommand("chown user file.txt"));
        assertFalse(PlanUtils.isSafeCommand("ln -s target link"));
        assertFalse(PlanUtils.isSafeCommand("sudo ls"));
    }

    @Test
    void destructivePackageCommands() {
        assertFalse(PlanUtils.isSafeCommand("npm install express"));
        assertFalse(PlanUtils.isSafeCommand("npm uninstall express"));
        assertFalse(PlanUtils.isSafeCommand("yarn add express"));
        assertFalse(PlanUtils.isSafeCommand("pip install requests"));
        assertFalse(PlanUtils.isSafeCommand("apt-get install vim"));
    }

    @Test
    void destructiveGitCommands() {
        assertFalse(PlanUtils.isSafeCommand("git add ."));
        assertFalse(PlanUtils.isSafeCommand("git commit -m 'msg'"));
        assertFalse(PlanUtils.isSafeCommand("git push"));
        assertFalse(PlanUtils.isSafeCommand("git merge feature"));
        assertFalse(PlanUtils.isSafeCommand("git rebase main"));
    }

    @Test
    void redirectIsDestructive() {
        assertFalse(PlanUtils.isSafeCommand("echo hello > file.txt"));
    }

    @Test
    void appendRedirectIsDestructive() {
        assertFalse(PlanUtils.isSafeCommand("echo hello >> file.txt"));
    }

    @Test
    void unknownCommandsDefaultToUnsafe() {
        assertFalse(PlanUtils.isSafeCommand("custom-tool --flag"));
    }

    @Test
    void emptyCommandIsUnsafe() {
        assertFalse(PlanUtils.isSafeCommand(""));
    }
}
