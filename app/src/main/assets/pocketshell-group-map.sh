# Runs before guest login scripts; never changes process credentials.
# Use the distro's group tool so /etc/group and /etc/gshadow keep their normal locks.
build=$1
shift
diagnostic_dir=/var/lib/pocketshell/group-map

record_group() {
    group_id=$1
    state=$2
    result=$3
    if mkdir -p "$diagnostic_dir"; then
        record_tmp="$diagnostic_dir/.$group_id.$$"
        if printf 'v1\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$build" "$$" "$group_id" "$state" "$result" > "$record_tmp" \
            && mv -f "$record_tmp" "$diagnostic_dir/$group_id"; then
            return
        fi
        rm -f "$record_tmp"
    fi
    printf '%s\n' 'PocketShell: group-name repair diagnostics could not be saved.' >&2
}

# Numeric IDs remain the authority; a warm launch only needs one name lookup.
if ! id -Gn >/dev/null 2>&1; then
    if group_ids=$(id -G); then
        for gid in $group_ids; do
            case "$gid" in ''|*[!0-9]*) continue ;; esac
            if getent group "$gid" >/dev/null 2>&1; then continue; fi
            record_group "$gid" registering 0
            suffix=0
            while :; do
                name="pocketshell_android_$gid"
                if [ "$suffix" -gt 0 ]; then name="${name}_$suffix"; fi
                if getent group "$name" >/dev/null 2>&1; then
                    suffix=$((suffix + 1))
                    continue
                fi
                if command -v groupadd >/dev/null 2>&1; then
                    detail=$(groupadd -g "$gid" "$name" 2>&1)
                    result=$?
                elif command -v addgroup >/dev/null 2>&1; then
                    detail=$(addgroup -g "$gid" "$name" 2>&1)
                    result=$?
                else
                    detail='No groupadd or addgroup is available in this Linux installation.'
                    result=127
                fi
                # Another tab may have completed the same registration while we waited.
                if getent group "$gid" >/dev/null 2>&1; then
                    record_group "$gid" registered 0
                    break
                fi
                # A concurrent writer may have claimed the candidate name for another ID.
                if [ "$result" -ne 0 ] && getent group "$name" >/dev/null 2>&1; then
                    suffix=$((suffix + 1))
                    continue
                fi
                record_group "$gid" failed "$result"
                printf 'PocketShell: could not register Android group %s (exit %s). %s\n' "$gid" "$result" "$detail" >&2
                break
            done
        done
    else
        result=$?
        record_group process enumeration_failed "$result"
        printf '%s\n' 'PocketShell: could not read Android group IDs for Linux startup.' >&2
    fi
fi
# Reconcile an interrupted/failed prior attempt after a later successful database write.
# Completed records require no subprocesses or rewrites on warm launches.
for record in "$diagnostic_dir"/[0-9]*; do
    [ -f "$record" ] || continue
    record_name=${record##*/}
    case "$record_name" in ''|*[!0-9]*) continue ;; esac
    IFS="	" read -r diag_schema diag_at diag_build diag_pid diag_gid diag_state diag_code < "$record" || continue
    [ "$diag_schema" = v1 ] && [ "$diag_gid" = "$record_name" ] || continue
    case "$diag_state" in
        registering|failed)
            if getent group "$diag_gid" >/dev/null 2>&1; then record_group "$diag_gid" registered 0; fi ;;
    esac
done
# Positional arguments preserve quoting, exit status, signals and terminal job control.
exec "$@"
