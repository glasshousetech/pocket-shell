#define _GNU_SOURCE
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <poll.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static int count;
static void require(int ok, const char *label) { if (!ok) { fprintf(stderr, "FAIL %s errno=%d (%s)\n", label, errno, strerror(errno)); exit(1); } printf("PASS %s\n", label); count++; }
int main(void) {
 char base[]="/tmp/pocketshell-syscalls-XXXXXX", path[512], missing[512];
 require(mkdtemp(base)!=NULL,"isolated workspace");
 snprintf(path,sizeof(path),"%s/created",base); snprintf(missing,sizeof(missing),"%s/missing",base);
 require(syscall(SYS_access,base,F_OK)==0,"access existing guest path");
 errno=0; require(syscall(SYS_access,missing,F_OK)==-1 && errno==ENOENT,"access missing preserves ENOENT");
 require(syscall(SYS_mkdir,path,0700)==0,"mkdir guest path");
 errno=0; require(syscall(SYS_mkdir,path,0700)==-1 && errno==EEXIST,"mkdir existing preserves EEXIST");
 int fd=open("/dev/null",O_RDWR|O_CLOEXEC); require(fd>=0,"open descriptor");
 require(syscall(SYS_dup2,fd,fd)==fd && (fcntl(fd,F_GETFD)&FD_CLOEXEC),"dup2 same descriptor preserves number and flags");
 int target=fcntl(fd,F_DUPFD_CLOEXEC,fd+1); require(target>=0,"prepare distinct descriptor");
 require(syscall(SYS_dup2,fd,target)==target && !(fcntl(target,F_GETFD)&FD_CLOEXEC),"dup2 distinct descriptor clears CLOEXEC");
 close(target); errno=0; require(syscall(SYS_dup2,target,target)==-1 && errno==EBADF,"dup2 closed same descriptor preserves EBADF");
 errno=0; require(syscall(SYS_dup2,-1,-1)==-1 && errno==EBADF,"dup2 invalid same descriptor preserves EBADF");
 int pipefd[2]; require(syscall(SYS_pipe,pipefd)==0,"legacy pipe");
 struct pollfd ready={.fd=pipefd[0],.events=POLLIN};
 require(syscall(SYS_poll,&ready,1,0)==0,"poll empty immediate timeout");
 require(write(pipefd[1],"x",1)==1 && syscall(SYS_poll,&ready,1,-1)==1 && (ready.revents&POLLIN),"poll negative timeout with ready data");
 close(pipefd[0]);close(pipefd[1]);
 require(syscall(SYS_rename,path,missing)==0,"rename guest directory");
 errno=0; require(syscall(SYS_unlink,missing)==-1 && errno==EISDIR,"unlink directory preserves EISDIR");
 int filefd=open(path,O_CREAT|O_WRONLY,0600); require(filefd>=0,"create unlink fixture");close(filefd);
 require(syscall(SYS_unlink,path)==0,"unlink guest file");
 require(syscall(SYS_rename,missing,path)==0,"restore directory name");
 close(fd); require(rmdir(path)==0 && rmdir(base)==0,"fixture cleanup");
 printf("POCKETSHELL_SYSCALLS_OK %d\n",count); return 0;
}
