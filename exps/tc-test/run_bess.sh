#!/bin/bash

RUN_START=1
RUN_END=2


for i in $(seq $RUN_START $RUN_END)
do
    for qtype in bess-tc
    #for qtype in bess-sq bess-mq bess-tc
    do
        # Configure the network on all of the servers
        sudo -u ubuntu -H ./config_all_bess_netconf.sh $qtype.conf

        # Note: tcpdump has already been started as part of configuring BESS (fairnes.bess)
        #  However, in order to get this to work, bessctl is run in the background
        #  an may not be finished running yet.
        # For now, just sleep and hope BESS gets configured correctly.
        sleep 2
        ping 10.10.102.1 -c 1
        if [ $? -ne 0 ]
        then
            echo "BESS failed to configure correctly!"
            #exit 1
            continue
        fi

        sudo tcpdump -i loom1 -w /dev/shm/tctest_tcp_flows.$qtype.pcap -s 64 src 10.10.1.1 or src 10.10.101.1 or src 10.10.102.1 &
        #TODO: I could collect a trace from BESS internals as well

        time ./run_tc_test.py --configs configs/tctest_conf1.yaml --extra-name $qtype.$i --runs 1

        echo "After wait..."

        sudo killall tcpdump

        #./results_scripts/get_tenant_tput_ts.py --pcap /dev/shm/tctest_tcp_flows.$qtype.pcap --outf results/tputs.$qtype.$i.yaml

        #cp /dev/shm/tctest_tcp_flows.pcap results/tctest_tcp_flows.$qtype.pcap
        #sudo rm -f /dev/shm/tctest_tcp_flows.pcap
    done

    #./results_scripts/get_tenant_tput_ts.py --pcap /dev/shm/tctest_tcp_flows.bess-sq.pcap --outf results/tputs.bess-sq.$i.yaml &
    #./results_scripts/get_tenant_tput_ts.py --pcap /dev/shm/tctest_tcp_flows.bess-mq.pcap --outf results/tputs.bess-mq.$i.yaml &
    ./results_scripts/get_tenant_tput_ts.py --pcap /dev/shm/tctest_tcp_flows.bess-tc.pcap --outf results/tputs.bess-tc.$i.yaml

    ##sudo rm -f /dev/shm/tctest_tcp_flows.bess-sq.pcap

    #TODO: better waiting for all jobs to finish
done
