# Git commit messages (shell)

If the shell is **PowerShell** (`pwsh` / Windows), never use bash HEREDOC for commits (`git commit -m "$(cat <<'EOF' ...)"`). Use a here-string (`$msg = @"..."@; git commit -m $msg`) or a one-line `-m`. Bash/WSL: HEREDOC is fine.
