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
#include <sys/select.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <time.h>
#include <signal.h>
static int count;
static void require(int ok, const char *label) { if (!ok) { fprintf(stderr, "FAIL %s errno=%d (%s)\n", label, errno, strerror(errno)); exit(1); } printf("PASS %s\n", label); count++; }

static volatile sig_atomic_t select_signal;
static void select_signal_handler(int signal_number) { select_signal = signal_number; }
static long milliseconds(void) {
 struct timespec now;
 require(clock_gettime(CLOCK_MONOTONIC,&now)==0,"read monotonic clock");
 return now.tv_sec*1000L+now.tv_nsec/1000000;
}
static void test_select(void) {
 int fds[2]; fd_set readfds,writefds;
 struct timeval tv;
 require(pipe(fds)==0,"select fixture pipe");
 FD_ZERO(&readfds);FD_SET(fds[0],&readfds);tv=(struct timeval){0,0};
 require(syscall(SYS_select,fds[0]+1,&readfds,NULL,NULL,&tv)==0 && !FD_ISSET(fds[0],&readfds),"select clears unreadable fd on immediate timeout");
 FD_ZERO(&writefds);FD_SET(fds[1],&writefds);
 require(syscall(SYS_select,fds[1]+1,NULL,&writefds,NULL,NULL)==1 && FD_ISSET(fds[1],&writefds),"select writable fd with infinite timeout");
 require(write(fds[1],"s",1)==1,"prepare readable select fd");
 FD_ZERO(&readfds);FD_SET(fds[0],&readfds);tv=(struct timeval){2,0};
 require(syscall(SYS_select,fds[0]+1,&readfds,NULL,NULL,&tv)==1 && FD_ISSET(fds[0],&readfds) && (tv.tv_sec>0 || tv.tv_usec>0) && tv.tv_sec<2 && tv.tv_usec>=0 && tv.tv_usec<1000000,"select ready read preserves remaining timeval");
 char byte;require(read(fds[0],&byte,1)==1 && byte=='s',"select leaves readable bytes intact");
 tv=(struct timeval){0,20000};long start=milliseconds();
 require(syscall(SYS_select,0,NULL,NULL,NULL,&tv)==0,"select finite timeout succeeds");
 long elapsed=milliseconds()-start;
 require(elapsed>=10 && elapsed<5000 && tv.tv_sec==0 && tv.tv_usec==0,"select waits and writes back exhausted timeout");
 tv=(struct timeval){-1,0};errno=0;
 require(syscall(SYS_select,0,NULL,NULL,NULL,&tv)==-1 && errno==EINVAL,"select negative seconds preserves EINVAL");
 tv=(struct timeval){0,-1};errno=0;
 require(syscall(SYS_select,0,NULL,NULL,NULL,&tv)==-1 && errno==EINVAL,"select negative microseconds preserves EINVAL");
 errno=0;require(syscall(SYS_select,0,NULL,NULL,NULL,(void*)1)==-1 && errno==EFAULT,"select invalid timeval preserves EFAULT");
 tv=(struct timeval){0,0};errno=0;
 require(syscall(SYS_select,1,(void*)1,NULL,NULL,&tv)==-1 && errno==EFAULT,"select invalid fdset preserves EFAULT");
 tv=(struct timeval){0,0};errno=0;
 require(syscall(SYS_select,-1,NULL,NULL,NULL,&tv)==-1 && errno==EINVAL,"select negative nfds preserves EINVAL");
 FD_ZERO(&writefds);FD_SET(fds[1],&writefds);tv=(struct timeval){0,1500000};
 require(syscall(SYS_select,fds[1]+1,NULL,&writefds,NULL,&tv)==1 && tv.tv_sec==1 && tv.tv_usec>=0 && tv.tv_usec<500000,"select normalizes large microseconds like Linux");
 struct sigaction action={0},previous={0};
 action.sa_handler=select_signal_handler;sigemptyset(&action.sa_mask);
 require(sigaction(SIGUSR1,&action,&previous)==0,"select install owned signal handler");
 sigset_t signal_set,original_mask;sigemptyset(&signal_set);sigaddset(&signal_set,SIGUSR1);
 require(sigprocmask(SIG_UNBLOCK,&signal_set,&original_mask)==0,"select unblock owned test signal");
 printf("SELECT_SIGNAL_MASK inheritedBlocked=%d\n",sigismember(&original_mask,SIGUSR1));
 pid_t parent=getpid(),child=fork();require(child>=0,"select interruption child");
 if(child==0){usleep(50000);if(kill(parent,SIGUSR1)!=0){fprintf(stderr,"SELECT_SIGNAL_SEND errno=%d\n",errno);_exit(125);}_exit(0);}
 tv=(struct timeval){2,0};errno=0;
 long result=syscall(SYS_select,0,NULL,NULL,NULL,&tv);int saved_errno=errno;
 int status;require(waitpid(child,&status,0)==child && WIFEXITED(status) && WEXITSTATUS(status)==0,"select signal fixture finishes");
 printf("SELECT_INTERRUPTED result=%ld errno=%d signal=%d timeout=%ld.%06ld\n",result,saved_errno,(int)select_signal,(long)tv.tv_sec,(long)tv.tv_usec);
 require(result==-1 && saved_errno==EINTR && select_signal==SIGUSR1 && (tv.tv_sec>0||tv.tv_usec>0) && tv.tv_sec<2,"select interruption returns EINTR and remaining timeout");
 require(sigaction(SIGUSR1,&previous,NULL)==0,"select restore signal handler");
 require(sigprocmask(SIG_SETMASK,&original_mask,NULL)==0,"select restore inherited signal mask");
 close(fds[0]);FD_ZERO(&readfds);FD_SET(fds[0],&readfds);tv=(struct timeval){0,0};errno=0;
 require(syscall(SYS_select,fds[1]+1,&readfds,NULL,NULL,&tv)==-1 && errno==EBADF,"select closed fd preserves EBADF");
 close(fds[1]);
}

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
 test_select();
 require(syscall(SYS_rename,path,missing)==0,"rename guest directory");
 errno=0; require(syscall(SYS_unlink,missing)==-1 && errno==EISDIR,"unlink directory preserves EISDIR");
 int filefd=open(path,O_CREAT|O_WRONLY,0600); require(filefd>=0,"create unlink fixture");close(filefd);
 require(syscall(SYS_unlink,path)==0,"unlink guest file");
 require(syscall(SYS_rename,missing,path)==0,"restore directory name");
 close(fd); require(rmdir(path)==0 && rmdir(base)==0,"fixture cleanup");
 printf("POCKETSHELL_SYSCALLS_OK %d\n",count); return 0;
}
