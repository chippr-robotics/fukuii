// B-527: sbt-git reads the repository with JGit by default, and JGit cannot open a linked git worktree (there `.git`
// is a file pointing at the main repository, not a directory): project load fails with NoWorkTreeException. In a
// linked worktree, read the repository through the git command line instead. A normal clone is unchanged.
if (new java.io.File(".git").isFile) Seq(com.github.sbt.git.SbtGit.useReadableConsoleGit) else Seq.empty
