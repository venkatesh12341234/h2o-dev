#----------------------------------------------------------------------
# Purpose:  This test exercises building GLM/GBM/DL  model 
#           for 376K rows and 6.9K columns 
#----------------------------------------------------------------------
    
setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"f")))
source('../h2o-runit-hadoop.R') 

ipPort <- get_args(commandArgs(trailingOnly = TRUE))
myIP   <- ipPort[[1]]
myPort <- ipPort[[2]]
hdfs_name_node <- Sys.getenv(c("NAME_NODE"))
print(hdfs_name_node)

library(RCurl)
library(h2o)

running_inside_hexdata = file.exists("/mnt/0xcustomer-datasets/c28")

if (!running_inside_hexdata) {
    # hdp2.2 cluster
    stop("0xdata internal test and data.")
}

heading("BEGIN TEST")
conn <- h2o.init(ip=myIP, port=myPort, startH2O = FALSE)
h2o.removeAll()

h2o.ls(conn)
#----------------------------------------------------------------------
# Parameters for the test.
#----------------------------------------------------------------------
data.hex <- h2o.importFile(conn, "/mnt/0xcustomer-datasets/c28/mr_output.tsv.sorted.gz", key="lob_mr_data)

dim(data.hex)

s = h2o.runif(data.hex)
train = data.hex[s <= 0.8,]
valid = data.hex[s > 0.8,]

model.gbm <- h2o.gbm(x = 3:(ncol(train)), y = 2,
                     training_frame = train, validation_frame=valid,
                     ntrees=50, max_depth=5
                     )

pred = predict(model.gbm, valid)
perf <- h2o.performance(model.gbm, valid)

PASS_BANNER()
