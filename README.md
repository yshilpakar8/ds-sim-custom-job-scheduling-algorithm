
## IMPORTANT INFO
After creating the codespace, please execute 
```bash
chmod +x ds-*
```
in the terminal. 
You only need to do this for once after the creation of the codespace.

---

## Overview
ds-sim is a discrete-event simulator that has been developed primarily for leveraging scheduling algorithm design. 

It adopts a minimalist design explicitly taking into account modularity in that it uses the client-server model. 

The client-side simulator acts as a job scheduler while the server-side simulator simulates everything else including users (job submissions) and servers (job execution).


---


## How to run a simulation:
1. run server `$ ds-server [OPTION]...`
2. run client `$ ds-client [-a algorithm] [OPTION]...`

## Usage example:
in one terminal, execute the ds-server:
```bash
./ds-server -n -p 50000 -v brief -c ./configs/sample-configs/ds-sample-config01.xml 
```

in other one, execute the ds-client:
```bash
./ds-client -n -p 50000 -a bf
```

